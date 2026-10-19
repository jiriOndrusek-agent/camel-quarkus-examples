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
package org.acme.partner.gateway;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisabledIfSystemProperty(named = "skip-testcontainers-tests", matches = ".*", disabledReason = "Requires containers")
class AuditTrailTest extends AbstractGatewayTest {

    private static final String ACME = "service-account-partner-acme";
    private static final String GLOBEX = "service-account-partner-globex";

    @Test
    void ordersAreAudited() {
        long start = lastSeq();
        String orderId = createOrder(acmeToken());
        given().auth().oauth2(acmeToken()).get("/api/orders/" + orderId).then().statusCode(200);
        given().auth().oauth2(globexToken()).get("/api/orders/" + orderId).then().statusCode(404);
        postOrder(acmeToken(), newKey(), "{\"sku\":\"WIDGET-1\",\"quantity\":0}").then().statusCode(400);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Map<String, Object>> events = since(start);
            assertAudited(events, "ORDER_ACCEPTED", "order/" + orderId, ACME, "CLIENT", "acme", "SUCCESS");
            assertAudited(events, "ORDER_READ", "order/" + orderId, ACME, "CLIENT", "acme", "SUCCESS");
            assertAudited(events, "ORDER_READ_DENIED", "order/" + orderId, GLOBEX, "CLIENT", "globex", "DENIED");
            assertAudited(events, "REQUEST_REJECTED", "route/order-create", ACME, "CLIENT", "acme", "REJECTED");
            assertAudited(events, "AUDIT_QUERIED", "audit", "aaron", "USER", null, "SUCCESS");
        });
    }

    @Test
    void requestsRejectedByQuarkusAreAudited() {
        long start = lastSeq();
        String path = "/api/orders/" + UUID.randomUUID();
        given().auth().oauth2("garbage").get(path).then().statusCode(401);
        given().get(path).then().statusCode(401);
        given().auth().oauth2(acmeToken()).get("/api/audit/verify").then().statusCode(403);

        // Quarkus security events are observed asynchronously
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Map<String, Object>> events = since(start);
            assertAudited(events, "AUTHN_FAILED", "GET " + path, "anonymous", "ANONYMOUS", null, "DENIED");
            assertAudited(events, "AUTHZ_DENIED", "GET " + path, "anonymous", "ANONYMOUS", null, "DENIED");
            assertAudited(events, "AUTHZ_DENIED", "GET /api/audit/verify", ACME, "CLIENT", "acme", "DENIED");
        });
    }

    @Test
    void camelLifecycleIsAudited() {
        List<Map<String, Object>> started = audit("?action=CONTEXT_STARTED");
        assertAudited(started, "CONTEXT_STARTED", "camel-context/secure-partner-gateway", "gateway", "SYSTEM", null,
                "SUCCESS");
        assertAudited(audit("?action=ROUTE_STARTED&limit=100"), "ROUTE_STARTED", "route/order-create", "gateway",
                "SYSTEM", null, "SUCCESS");
    }

    @Test
    void invalidQueryIsRejected() {
        for (String limit : new String[] { "all", "0" }) {
            given().auth().oauth2(auditorToken()).get("/api/audit?limit=" + limit)
                    .then().statusCode(400)
                    .contentType("application/problem+json");
        }
    }

    @Test
    void concurrentAppendsKeepTheChainValid() throws Exception {
        String token = acmeToken();

        List<Response> responses = concurrently(8, () -> postOrder(token, newKey(), ORDER));

        assertTrue(responses.stream().allMatch(response -> response.statusCode() == 202),
                () -> responses.stream().map(Response::statusCode).toList().toString());
        given().auth().oauth2(auditorToken()).get("/api/audit/verify")
                .then().statusCode(200)
                .body("valid", is(true));
    }

    @Test
    void auditChainIsValid() {
        given().auth().oauth2(auditorToken()).get("/api/audit/verify")
                .then().statusCode(200)
                .body("valid", is(true))
                .body("checked", greaterThan(0))
                .body("brokenAtSeq", nullValue());
    }

    private List<Map<String, Object>> since(long seq) {
        return audit("?limit=500").stream()
                .filter(event -> ((Number) event.get("seq")).longValue() > seq)
                .toList();
    }

    private static void assertAudited(List<Map<String, Object>> events, String action, String resource, String actor,
            String actorType, String partnerId, String outcome) {
        assertTrue(events.stream().anyMatch(event -> action.equals(event.get("action"))
                && resource.equals(event.get("resource"))
                && actor.equals(event.get("actor"))
                && actorType.equals(event.get("actorType"))
                && Objects.equals(partnerId, event.get("partnerId"))
                && outcome.equals(event.get("outcome"))),
                () -> "No " + action + " " + outcome + " record of " + actor + " for " + resource + " in " + events);
    }
}
