/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.acme.ai.tool.support.desk;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkiverse.mcp.server.test.McpAssured.ToolAnnotations;
import io.quarkiverse.mcp.server.test.McpAssured.ToolInfo;
import io.quarkiverse.mcp.server.test.McpAssured.ToolsPage;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.acme.ai.tool.support.desk.SupportDesk.auditCount;
import static org.acme.ai.tool.support.desk.SupportDesk.killSwitch;
import static org.acme.ai.tool.support.desk.SupportDesk.returnStatus;
import static org.acme.ai.tool.support.desk.SupportDeskTestResource.FAILED_REFUNDS_OF_RETURN_1;
import static org.acme.ai.tool.support.desk.SupportDeskTestResource.refundAmounts;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the MCP server of the support desk, the way an AI assistant of the support staff uses it. Returns 1, 2 and
 * 3 of the demo data wait for approval.
 */
@QuarkusTest
@QuarkusTestResource(OllamaTestResource.class)
@QuarkusTestResource(SupportDeskTestResource.class)
class SupportDeskMcpTest {

    private McpStreamableTestClient client;

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
    }

    @Test
    void onlySupportStaffCanConnect() {
        McpAssured.newStreamableClient()
                .setBaseUri(baseUri())
                .setExpectConnectFailure(response -> assertEquals(401, response.statusCode()))
                .build()
                .connect();

        McpAssured.newStreamableClient()
                .setBaseUri(baseUri())
                .setBasicAuth("alice", "alice")
                .setExpectConnectFailure(response -> assertEquals(403, response.statusCode()))
                .build()
                .connect();
    }

    @Test
    void onlySupportToolsArePublishedWithTheirHints() {
        client().when()
                .toolsList(page -> {
                    assertEquals(List.of("decide_return", "list_pending_returns"), toolNames(page));

                    ToolAnnotations list = annotations(page, "list_pending_returns");
                    assertTrue(list.readOnlyHint());
                    assertFalse(list.openWorldHint());

                    ToolAnnotations decide = annotations(page, "decide_return");
                    assertFalse(decide.readOnlyHint());
                    assertTrue(decide.destructiveHint());
                    assertTrue(decide.idempotentHint());
                })
                .thenAssertResults();
    }

    @Test
    void approvedReturnIsRefundedOnceEvenAfterPaymentFailures() {
        // One call per block: the calls of a block are sent together and may run in any order
        client().when()
                .toolsCall("list_pending_returns", Map.of(), response -> {
                    JsonObject headphones = pendingReturns(response).stream()
                            .map(JsonObject.class::cast)
                            .filter(r -> r.getInteger("returnId") == 1)
                            .findFirst()
                            .orElseThrow();
                    assertEquals("Headphones H2", headphones.getJsonArray("items").getJsonObject(0).getString("name"));
                    // The reason is a code rather than the customer's words, and there are no contact details
                    assertEquals("DEFECTIVE", headphones.getString("reason"));
                    assertFalse(text(response).contains("@"));
                })
                .thenAssertResults();
        client().when()
                .toolsCall("decide_return",
                        Map.of("returnId", 1, "decision", "approve", "note", "The headphones are defective"),
                        response -> assertFalse(response.isError()))
                .thenAssertResults();

        // The payment provider fails the first refund calls for return 1, asking to try later or being down (see
        // SupportDeskTestResource). The refund route retries, gives up, and the refund-dispatcher route hands the
        // return over again, until it is refunded: every call carries the same idempotency key, and the provider
        // accepts one of them.
        await().atMost(30, TimeUnit.SECONDS).until(() -> returnStatus("bob", 2001), is("REFUNDED"));
        assertEquals(Collections.nCopies(FAILED_REFUNDS_OF_RETURN_1.size() + 1, 89.0), refundAmounts(1));

        // Deciding again changes nothing, so the customer is not refunded twice
        client().when()
                .toolsCall("decide_return", Map.of("returnId", 1, "decision", "approve"),
                        response -> assertTrue(text(response).contains("nothing changed")))
                .thenAssertResults();
        assertEquals("REFUNDED", returnStatus("bob", 2001));
        assertEquals(FAILED_REFUNDS_OF_RETURN_1.size() + 1, refundAmounts(1).size());
        assertTrue(auditCount("decide_return", "mcp", "OK") >= 2);
    }

    @Test
    void refusedRefundIsParked() {
        client().when()
                .toolsCall("decide_return", Map.of("returnId", 3, "decision", "approve", "note", "Damaged in transit"),
                        response -> assertFalse(response.isError()))
                .thenAssertResults();

        // The payment provider refuses the refund of return 3 (see SupportDeskTestResource): asking again would not
        // help, so the return is parked rather than tried again and again
        await().atMost(30, TimeUnit.SECONDS).until(() -> returnStatus("bob", 2005), is("REFUND_FAILED"));
        // It stays parked after one call, also once the refund-dispatcher route would have sent an approved return again
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS)
                .until(() -> refundAmounts(3).equals(List.of(199.0)) && "REFUND_FAILED".equals(returnStatus("bob", 2005)));

        // The support staff see the parked return
        client().when()
                .toolsCall("list_pending_returns", Map.of(), response -> assertEquals("REFUND_FAILED",
                        pendingReturns(response).stream()
                                .map(JsonObject.class::cast)
                                .filter(r -> r.getInteger("returnId") == 3)
                                .findFirst()
                                .orElseThrow()
                                .getString("status")))
                .thenAssertResults();
    }

    @Test
    void rejectedReturnIsNotRefunded() {
        client().when()
                .toolsCall("decide_return", Map.of("returnId", 2, "decision", "reject", "note", "The keyboard is worn out"),
                        response -> assertFalse(response.isError()))
                .thenAssertResults();

        assertEquals("REJECTED", returnStatus("bob", 2002));
        assertEquals(List.of(), refundAmounts(2));
    }

    @Test
    void failedToolCallRevealsNoDetailsAndIsAudited() {
        client().when()
                .toolsCall("decide_return", Map.of("returnId", "not-a-number", "decision", "approve"), response -> {
                    assertTrue(response.isError());
                    assertEquals("Tool execution failed", text(response));
                })
                .thenAssertResults();

        assertTrue(auditCount("decide_return", "mcp", "FAILED") >= 1);
    }

    @Test
    void killSwitchRemovesAndRestoresATool() {
        killSwitch("alice", "list_pending_returns", "disable").statusCode(403);
        // Only tool routes have a kill switch
        killSwitch("carol", "refund", "disable").statusCode(404);

        try {
            killSwitch("carol", "list_pending_returns", "disable").statusCode(204);
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> client().when()
                    .toolsList(page -> assertEquals(List.of("decide_return"), toolNames(page)))
                    .thenAssertResults());
        } finally {
            killSwitch("carol", "list_pending_returns", "enable").statusCode(204);
        }
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> client().when()
                .toolsList(page -> assertEquals(List.of("decide_return", "list_pending_returns"), toolNames(page)))
                .thenAssertResults());
    }

    private McpStreamableTestClient client() {
        if (client == null) {
            client = McpAssured.newStreamableClient()
                    .setBaseUri(baseUri())
                    .setBasicAuth("carol", "carol")
                    .build()
                    .connect();
        }
        return client;
    }

    private static URI baseUri() {
        // RestAssured knows the port of the application under test on every thread, the config only on the test thread
        return URI.create("http://localhost:" + RestAssured.port);
    }

    private static List<String> toolNames(ToolsPage page) {
        return page.tools().stream().map(ToolInfo::name).sorted().toList();
    }

    private static ToolAnnotations annotations(ToolsPage page, String tool) {
        return page.tools().stream()
                .filter(t -> tool.equals(t.name()))
                .findFirst()
                .orElseThrow()
                .annotations()
                .orElseThrow();
    }

    private static JsonArray pendingReturns(ToolResponse response) {
        return new JsonArray(text(response));
    }

    private static String text(ToolResponse response) {
        return response.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .collect(Collectors.joining());
    }
}
