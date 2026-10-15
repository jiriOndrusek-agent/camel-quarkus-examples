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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.quarkus.test.common.DevServicesContext;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.restassured.path.json.JsonPath;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.ConfigProvider;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Configures the support desk for the tests: the users, and WireMock standing in for the carrier and the payment
 * provider, so that the tests can check which calls were made and simulate failures. It also reads the Kafka topics.
 */
public class SupportDeskTestResource implements QuarkusTestResourceLifecycleManager, DevServicesContext.ContextAware {

    /**
     * The answers of the payment provider to the first refund calls for return 1: as many failures as the refund route
     * makes calls for one Kafka message (the call and its 3 redeliveries), so that the refund-dispatcher route has to
     * hand the return over again. The provider asks to try later (HTTP 429 and 408) or is down (HTTP 503).
     */
    static final List<Integer> FAILED_REFUNDS_OF_RETURN_1 = List.of(429, 408, 503, 503);

    private static WireMockServer wireMock;
    private static StubMapping carrierOutage;
    private static String kafkaBootstrapServers;

    @Override
    public void setIntegrationTestContext(DevServicesContext context) {
        // The Kafka started by Dev Services, also when the tests run against the packaged application
        kafkaBootstrapServers = context.devServicesProperties().get("kafka.bootstrap.servers");
    }

    @Override
    public Map<String, String> start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(get(urlPathMatching("/carrier/shipments/.+"))
                .willReturn(okJson("{\"status\": \"OUT_FOR_DELIVERY\", \"estimatedDelivery\": \"today\"}")));
        wireMock.stubFor(post(urlPathEqualTo("/payments/refunds"))
                .willReturn(okJson("{\"status\": \"ACCEPTED\"}")));
        // An outage of the payment provider for the refund of return 1
        String state = Scenario.STARTED;
        for (int failure = 0; failure < FAILED_REFUNDS_OF_RETURN_1.size(); failure++) {
            wireMock.stubFor(post(urlPathEqualTo("/payments/refunds"))
                    .withHeader("Idempotency-Key", equalTo("return-1"))
                    .inScenario("payment outage")
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withStatus(FAILED_REFUNDS_OF_RETURN_1.get(failure)))
                    .willSetStateTo("failure " + failure));
            state = "failure " + failure;
        }
        // The payment provider refuses the refund of return 3 for good
        wireMock.stubFor(post(urlPathEqualTo("/payments/refunds"))
                .withHeader("Idempotency-Key", equalTo("return-3"))
                .willReturn(aResponse().withStatus(422)));

        Map<String, String> config = new HashMap<>();
        config.put("support-desk.carrier.url", wireMock.baseUrl() + "/carrier");
        config.put("support-desk.payment.url", wireMock.baseUrl() + "/payments");
        // Hand a return that is still not refunded over again after 2 seconds rather than a minute
        config.put("support-desk.refunds.retry-after-seconds", "2");
        // The demo users of dev mode, whose password is their user name. dave is a customer who is not verified.
        config.put("quarkus.security.users.embedded.plain-text", "true");
        Map.of("alice", "customer,verified", "bob", "customer,verified", "carol", "support", "dave", "customer")
                .forEach((user, roles) -> {
                    config.put("quarkus.security.users.embedded.users." + user, user);
                    config.put("quarkus.security.users.embedded.roles." + user, roles);
                });
        return config;
    }

    @Override
    public void stop() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    /**
     * The amounts of the refunds requested from the payment provider for the given return, one per call.
     */
    static List<Double> refundAmounts(int returnId) {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo("/payments/refunds"))
                .withHeader("Idempotency-Key", equalTo("return-" + returnId)))
                .stream()
                .map(request -> JsonPath.from(request.getBodyAsString()).getDouble("amount"))
                .toList();
    }

    /**
     * Simulates an outage of the carrier API, or ends it.
     */
    static void carrierUnavailable(boolean unavailable) {
        if (unavailable) {
            carrierOutage = wireMock.stubFor(get(urlPathMatching("/carrier/shipments/.+")).willReturn(serviceUnavailable()));
        } else {
            wireMock.removeStub(carrierOutage);
        }
    }

    /**
     * The values of the messages on the Kafka topic so far.
     */
    static List<String> kafkaMessages(String topic) {
        String bootstrapServers = kafkaBootstrapServers != null ? kafkaBootstrapServers
                : ConfigProvider.getConfig().getValue("kafka.bootstrap.servers", String.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(partition -> new TopicPartition(topic, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<String> messages = new ArrayList<>();
            while (partitions.stream().anyMatch(partition -> consumer.position(partition) < endOffsets.get(partition))) {
                consumer.poll(Duration.ofMillis(500)).forEach(message -> messages.add(message.value()));
            }
            return messages;
        }
    }

    /**
     * How many times the carrier was asked about the given shipment with the given HTTP header.
     */
    static int trackingRequestsWithHeader(String trackingNumber, String header) {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo("/carrier/shipments/" + trackingNumber))
                .withHeader(header, matching(".*"))).size();
    }

    /**
     * How many times the carrier was asked about the given shipment.
     */
    static int trackingRequests(String trackingNumber) {
        return wireMock.findAll(getRequestedFor(urlPathEqualTo("/carrier/shipments/" + trackingNumber))).size();
    }
}
