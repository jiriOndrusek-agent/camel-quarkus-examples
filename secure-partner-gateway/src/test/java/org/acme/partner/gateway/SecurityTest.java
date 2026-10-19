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

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@DisabledIfSystemProperty(named = "skip-testcontainers-tests", matches = ".*", disabledReason = "Requires containers")
class SecurityTest extends AbstractGatewayTest {

    // Root of the management interface, set for both test modes
    @TestHTTPResource(value = "", management = true)
    URL management;

    @Test
    void requestWithoutTokenIsUnauthorized() {
        given().get("/api/orders/" + UUID.randomUUID()).then().statusCode(401);
        given().header(IDEMPOTENCY_KEY, newKey()).contentType("application/json").body(ORDER)
                .post("/api/orders").then().statusCode(401);
    }

    @Test
    void garbageAndTamperedTokensAreUnauthorized() {
        // Changing a claim, for example the tenant, breaks the signature
        String[] parts = acmeToken().split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"acme\"", "\"globex\"");
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        for (String token : new String[] { "garbage", tampered }) {
            given().auth().oauth2(token).get("/api/orders/" + UUID.randomUUID()).then().statusCode(401);
        }
    }

    @Test
    void expiredTokenIsUnauthorized() {
        // short-lived-client tokens expire after 3 seconds and carry no role
        String token = keycloak.getClientAccessToken("short-lived-client", "short-lived-test-secret");
        given().auth().oauth2(token).get("/api/audit").then().statusCode(403);

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> given().auth().oauth2(token).get("/api/audit").then().statusCode(401));
    }

    @Test
    void tokenFromAnotherIssuerIsUnauthorized() {
        // Keycloak in dev mode takes the issuer from the request URL: the token is signed by the right key,
        // but its issuer is not the configured one
        URI authServer = URI.create(keycloak.getAuthServerUrl());
        String otherHost = "localhost".equals(authServer.getHost()) ? "127.0.0.1" : "localhost";
        String otherIssuer = authServer.toString().replace("//" + authServer.getHost() + ":", "//" + otherHost + ":");
        String token = new KeycloakTestClient(otherIssuer).getClientAccessToken("partner-acme", "acme-test-secret");

        given().auth().oauth2(token).get("/api/orders/" + UUID.randomUUID()).then().statusCode(401);
    }

    @Test
    void tokenForAnotherAudienceIsUnauthorized() {
        // admin-cli is a built-in Keycloak client, its tokens are not issued for gateway-api
        String token = keycloak.getAccessToken("aaron", "aaron-test-password", "admin-cli", null);

        given().auth().oauth2(token).get("/api/audit").then().statusCode(401);
    }

    @Test
    void wrongRoleIsForbidden() {
        given().auth().oauth2(acmeToken()).get("/api/audit").then().statusCode(403);
        given().auth().oauth2(acmeToken()).get("/api/audit/verify").then().statusCode(403);
        postOrder(auditorToken(), newKey(), ORDER).then().statusCode(403);
    }

    @Test
    void unlistedPathsAndMethodsAreDenied() {
        String token = acmeToken();
        given().auth().oauth2(token).get("/api/orders").then().statusCode(403);
        given().auth().oauth2(token).delete("/api/orders/" + UUID.randomUUID()).then().statusCode(403);
        given().auth().oauth2(token).get("/api/unknown").then().statusCode(403);
    }

    @Test
    void healthAndMetricsAreOnTheManagementPortOnly() throws Exception {
        URI managementRoot = management.toURI();
        given().get(managementRoot.resolve("/observe/health")).then().statusCode(200);
        given().get(managementRoot.resolve("/observe/metrics"))
                .then().statusCode(200)
                .body(containsString("audit_events_total"));

        given().auth().oauth2(acmeToken()).get("/observe/health").then().statusCode(403);
    }

    @Test
    void partnerTokenWithoutTenantIsForbidden() {
        String token = keycloak.getClientAccessToken("partner-without-tenant", "without-tenant-test-secret");
        postOrder(token, newKey(), ORDER).then().statusCode(403).contentType("application/problem+json");
        given().auth().oauth2(token).get("/api/orders/" + UUID.randomUUID()).then().statusCode(403);

        assertTrue(audit("?action=AUTHZ_DENIED&limit=500").stream()
                .anyMatch(event -> "service-account-partner-without-tenant".equals(event.get("actor"))
                        && "partner token without partner_id claim".equals(event.get("reason"))));
    }

    @Test
    void partnerCannotSeeOrdersOfAnotherPartner() {
        String orderId = createOrder(acmeToken());

        String otherPartners = given().auth().oauth2(globexToken()).get("/api/orders/" + orderId)
                .then().statusCode(404).extract().asString();
        String unknown = given().auth().oauth2(globexToken()).get("/api/orders/" + UUID.randomUUID())
                .then().statusCode(404).extract().asString();
        // Nothing tells an existing order of another partner from an order that does not exist
        assertEquals(unknown, otherPartners);

        given().auth().oauth2(acmeToken()).get("/api/orders/" + orderId).then().statusCode(200);
    }

    @Test
    void tenantComesFromTheTokenOnly() {
        String orderId = given().auth().oauth2(acmeToken())
                .header("X-Partner-Id", "globex")
                .header("partnerId", "globex")
                .header(IDEMPOTENCY_KEY, newKey())
                .contentType("application/json")
                .body(ORDER)
                .post("/api/orders")
                .then().statusCode(202).extract().path("orderId");

        given().auth().oauth2(globexToken()).get("/api/orders/" + orderId).then().statusCode(404);
        given().auth().oauth2(acmeToken()).get("/api/orders/" + orderId).then().statusCode(200);
    }

    @Test
    void responsesDoNotEchoRequestHeaders() {
        String token = acmeToken();
        Response accepted = given().auth().oauth2(token)
                .cookie("session", "secret-cookie")
                .header("X-Probe", "probe")
                .header(IDEMPOTENCY_KEY, newKey())
                .contentType("application/json")
                .body(ORDER)
                .post("/api/orders");
        Response rejected = given().auth().oauth2(token)
                .header("X-Probe", "probe")
                .header(IDEMPOTENCY_KEY, newKey())
                .contentType("application/json")
                .body("{\"sku\":\"WIDGET-1\"}")
                .post("/api/orders");
        Response read = given().auth().oauth2(token)
                .header("X-Probe", "probe")
                .get("/api/orders/" + accepted.path("orderId"));

        // Camel copies the remaining headers of the exchange into the response, the allow-list keeps them out
        for (Response response : new Response[] { accepted, rejected, read }) {
            response.then()
                    .header("X-Probe", nullValue())
                    .header("Authorization", nullValue())
                    .header("Cookie", nullValue())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(not(containsString(token)));
        }
        accepted.then().statusCode(202);
        rejected.then().statusCode(400);
        read.then().statusCode(200);
    }
}
