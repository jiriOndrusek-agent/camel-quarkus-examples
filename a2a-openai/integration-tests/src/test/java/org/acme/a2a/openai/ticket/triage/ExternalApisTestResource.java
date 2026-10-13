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

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Simulates the carrier and the payment provider APIs that the support desk calls, so that the tests can check which
 * calls were made.
 */
public class ExternalApisTestResource implements QuarkusTestResourceLifecycleManager {

    private static WireMockServer wireMock;

    @Override
    public Map<String, String> start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlPathMatching("/carrier/shipments/.+"))
                .willReturn(okJson("{\"status\": \"OUT_FOR_DELIVERY\", \"estimatedDelivery\": \"today\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/payments/refunds"))
                .willReturn(okJson("{\"status\": \"ACCEPTED\"}")));
        return Map.of(
                "support-desk.carrier.url", wireMock.baseUrl() + "/carrier",
                "support-desk.payment.url", wireMock.baseUrl() + "/payments");
    }

    @Override
    public void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    /**
     * How many refunds were requested from the payment provider for the given return.
     */
    public static int refundRequests(int returnId) {
        return wireMock.countRequestsMatching(postRequestedFor(urlPathEqualTo("/payments/refunds"))
                .withHeader("Idempotency-Key", equalTo("return-" + returnId))
                .build())
                .getCount();
    }

    /**
     * How many times the carrier was asked about the given shipment.
     */
    public static int trackingRequests(String trackingNumber) {
        return wireMock.countRequestsMatching(getRequestedFor(urlPathEqualTo("/carrier/shipments/" + trackingNumber))
                .build())
                .getCount();
    }
}
