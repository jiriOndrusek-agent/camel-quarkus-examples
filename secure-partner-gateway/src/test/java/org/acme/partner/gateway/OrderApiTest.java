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

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisabledIfSystemProperty(named = "skip-testcontainers-tests", matches = ".*", disabledReason = "Requires containers")
class OrderApiTest extends AbstractGatewayTest {

    @Test
    void acceptedOrderCanBeRead() {
        String token = acmeToken();
        String orderId = postOrder(token, newKey(), "{\"sku\":\"WIDGET-7\",\"quantity\":3}")
                .then().statusCode(202).contentType("application/json")
                .extract().path("orderId");

        given().auth().oauth2(token).get("/api/orders/" + orderId)
                .then().statusCode(200)
                .contentType("application/json")
                .body("orderId", is(orderId))
                .body("sku", is("WIDGET-7"))
                .body("quantity", is(3));
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        postOrder(acmeToken(), null, ORDER)
                .then().statusCode(400)
                .contentType("application/problem+json")
                .body("status", is(400));
    }

    @Test
    void requestsBreakingTheRestContractAreRejected() {
        // checked by platform-http, before the route runs
        String token = acmeToken();
        given().auth().oauth2(token).header(IDEMPOTENCY_KEY, newKey())
                .contentType("text/plain").body("WIDGET-1 x 2")
                .post("/api/orders")
                .then().statusCode(415);
        given().auth().oauth2(token).header(IDEMPOTENCY_KEY, newKey())
                .contentType("application/json").accept("application/xml").body(ORDER)
                .post("/api/orders")
                .then().statusCode(406);
    }

    @Test
    void invalidOrdersAreRejectedWithoutInternals() {
        String token = acmeToken();
        String[] invalidOrders = {
                // malformed JSON
                "{\"sku\":",
                "{\"sku\":\"WIDGET-1\",\"quantity\":0}",
                // the tenant cannot be smuggled in through the body
                "{\"sku\":\"WIDGET-1\",\"quantity\":1,\"partnerId\":\"globex\"}",
        };
        for (String order : invalidOrders) {
            postOrder(token, newKey(), order)
                    .then().statusCode(400)
                    .contentType("application/problem+json")
                    .body("type", is("about:blank"))
                    .body("title", is("Bad Request"))
                    .body("status", is(400))
                    .body("detail", is("The request does not match the API contract"))
                    .body(not(containsString("Exception")))
                    .body(not(containsString("org.")));
        }
    }

    @Test
    void oversizedBodyIsRejected() {
        String order = "{\"sku\":\"WIDGET-1\",\"quantity\":1,\"note\":\"" + "x".repeat(70 * 1024) + "\"}";

        postOrder(acmeToken(), newKey(), order).then().statusCode(413);
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsTheFirstOrder() {
        String token = acmeToken();
        String key = newKey();
        int accepted = audit("?action=ORDER_ACCEPTED&limit=500").size();

        String first = postOrder(token, key, ORDER).then().statusCode(202).extract().path("orderId");
        String retry = postOrder(token, key, ORDER).then().statusCode(200).extract().path("orderId");

        assertEquals(first, retry);
        // a single order was stored: one more ORDER_ACCEPTED, and the retry was answered from it
        assertEquals(accepted + 1, audit("?action=ORDER_ACCEPTED&limit=500").size());
        assertEquals("order/" + first, audit("?action=ORDER_REPLAYED&limit=1").get(0).get("resource"));
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneOrder() throws Exception {
        String token = acmeToken();
        String key = newKey();
        int accepted = audit("?action=ORDER_ACCEPTED&limit=500").size();

        List<Response> responses = concurrently(8, () -> postOrder(token, key, ORDER));

        // one request creates the order, the others get it (200) or are told to retry (409), none fails
        List<Integer> statuses = responses.stream().map(Response::statusCode).toList();
        assertEquals(1, statuses.stream().filter(status -> status == 202).count(), statuses::toString);
        assertTrue(statuses.stream().allMatch(status -> status == 202 || status == 200 || status == 409),
                statuses::toString);
        String orderId = responses.stream().filter(response -> response.statusCode() == 202).findFirst()
                .orElseThrow().path("orderId");
        responses.stream().filter(response -> response.statusCode() == 200)
                .forEach(response -> assertEquals(orderId, response.path("orderId")));

        postOrder(token, key, ORDER).then().statusCode(200).body("orderId", is(orderId));
        assertEquals(accepted + 1, audit("?action=ORDER_ACCEPTED&limit=500").size());
    }

    @Test
    void idempotencyKeysAreScopedToThePartner() {
        String key = newKey();

        String acmeOrder = postOrder(acmeToken(), key, ORDER).then().statusCode(202).extract().path("orderId");
        String globexOrder = postOrder(globexToken(), key, ORDER).then().statusCode(202).extract().path("orderId");

        assertNotEquals(acmeOrder, globexOrder);
    }

    @Test
    void burstFromOnePartnerIsThrottled() {
        // only this test uses partner-initech: its limit stays used up for the rest of the period
        String token = keycloak.getClientAccessToken("partner-initech", "initech-test-secret");
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(10))
                .until(() -> postOrder(token, newKey(), ORDER).statusCode() == 429);

        postOrder(token, newKey(), ORDER)
                .then().statusCode(429)
                .contentType("application/problem+json")
                .header("Retry-After", matchesPattern("[0-9]+"));
        // the limit is per partner
        postOrder(acmeToken(), newKey(), ORDER).then().statusCode(202);
    }
}
