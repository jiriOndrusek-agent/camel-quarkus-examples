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
package org.acme.a2a.openai.ticket.triage;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.acme.a2a.openai.ticket.triage.SupportDesk.as;
import static org.acme.a2a.openai.ticket.triage.SupportDesk.auditCount;
import static org.acme.a2a.openai.ticket.triage.SupportDesk.returnId;
import static org.acme.a2a.openai.ticket.triage.SupportDesk.returnStatus;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the customer chat of the support desk: the agent calls the ai-tool routes tagged {@code customer} on behalf
 * of the logged-in customer. The tests check what the tool calls did, in the database and on the external APIs, rather
 * than the wording of the replies.
 * <p>
 * With the simulated OpenAI API, each conversation turn is a pair of stub mappings in
 * {@code src/test/resources/mappings/support-desk-*.json}: the model first asks for a tool call, then answers once it
 * gets the tool result. The second stub only matches the expected tool result, so a tool returning something else
 * fails the test.
 */
@QuarkusTest
@QuarkusTestResource(OpenAiTestResource.class)
@QuarkusTestResource(ExternalApisTestResource.class)
class SupportDeskChatTest {

    @Test
    void chatIsForLoggedInCustomers() {
        given()
                .contentType(ContentType.JSON)
                .body(message("Where is my order 1003?"))
                .post("/support/chat")
                .then()
                .statusCode(401);

        // carol is support staff, not a customer
        chat("carol", "Where is my order 1003?")
                .statusCode(403);

        chat("alice", " ")
                .statusCode(400);
    }

    @Test
    void customerOnlySeesOwnOrders() {
        chat("alice", "What is the status of my order 1001?")
                .statusCode(200)
                .body("reply", not(emptyOrNullString()));

        // Order 2001 is bob's: get_order runs for alice and does not find it, so the model never sees bob's headphones
        chat("alice", "And what about order 2001?")
                .statusCode(200)
                .body("reply", not(containsStringIgnoringCase("headphones")));

        assertTrue(auditCount("get_order", "alice", "OK") >= 2);
    }

    @Test
    void cheapReturnIsApprovedAndRefunded() {
        chat("alice", "Please return the two USB-C cables of order 1001, they are too short.")
                .statusCode(200);

        // 2 x 12.50 EUR is approved right away and refunded through Kafka and the payment provider
        await().atMost(30, TimeUnit.SECONDS).until(() -> returnStatus("alice", 1001), is("REFUNDED"));
        assertEquals(1, ExternalApisTestResource.refundRequests(returnId("alice", 1001)));
    }

    @Test
    void expensiveReturnWaitsForApproval() {
        chat("alice", "I want to return the smart speaker of order 1002, it does not connect to my wifi.")
                .statusCode(200);

        // 129 EUR needs the approval of the support staff, see SupportDeskMcpTest
        assertEquals("PENDING_APPROVAL", returnStatus("alice", 1002));
        assertEquals(0, ExternalApisTestResource.refundRequests(returnId("alice", 1002)));
    }

    @Test
    void itemListedTwiceIsNotRefundedTwice() {
        Assumptions.assumeTrue(OpenAiTestResource.isMocking(), "Only the simulated model lists an item twice on purpose");

        // Order 2003 holds one charger: requesting it twice is refused rather than refunded twice
        chat("bob", "Please return the charger of order 2003, I do not need it.")
                .statusCode(200);

        assertNull(returnStatus("bob", 2003));
    }

    @Test
    void shipmentIsTrackedWithTheCarrier() {
        chat("alice", "Where is my order 1003 right now?")
                .statusCode(200);

        assertTrue(ExternalApisTestResource.trackingRequests("ACME-7790") >= 1);
    }

    @Test
    void customerCanAskForAHuman() {
        chat("alice", "I want to talk to a human, the mouse of order 1003 is urgent for my work.")
                .statusCode(200);

        assertTrue(auditCount("escalate_to_human", "alice", "OK") >= 1);
    }

    private static ValidatableResponse chat(String customer, String message) {
        return as(customer)
                .contentType(ContentType.JSON)
                .body(message(message))
                .post("/support/chat")
                .then();
    }

    private static Map<String, String> message(String message) {
        return Map.of("message", message);
    }
}
