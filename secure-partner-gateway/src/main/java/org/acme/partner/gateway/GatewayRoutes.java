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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManagerFactory;
import org.acme.partner.gateway.audit.AuditLog;
import org.acme.partner.gateway.order.OrderRequest;
import org.acme.partner.gateway.order.OrderService;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jackson.JacksonDataFormat;
import org.apache.camel.component.jpa.TransactionStrategy;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class GatewayRoutes extends RouteBuilder {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    @Inject
    CallerIdentity callerIdentity;

    @Inject
    OrderService orderService;

    @Inject
    AuditLog auditLog;

    @Inject
    ObjectMapper mapper;

    @Inject
    EntityManagerFactory entityManagerFactory;

    @Inject
    TransactionStrategy transactionStrategy;

    @ConfigProperty(name = "gateway.throttle.max-requests")
    long throttleMaxRequests;

    @ConfigProperty(name = "gateway.throttle.period")
    Duration throttlePeriod;

    @ConfigProperty(name = "audit.anchor-period")
    Duration anchorPeriod;

    @Override
    public void configure() {
        JacksonDataFormat json = new JacksonDataFormat(mapper, Object.class);

        // 415 for a wrong Content-Type, 406 for an unacceptable Accept header
        restConfiguration().clientRequestValidation(true);

        rest("/api")
                .post("/orders").consumes("application/json").produces("application/json").to("direct:order-create")
                .get("/orders/{id}").produces("application/json").to("direct:order-read")
                .get("/audit").produces("application/json").to("direct:audit-query")
                .get("/audit/verify").produces("application/json").to("direct:audit-verify");

        // Every route starts at the trust boundary: the caller comes from the verified token, then all request
        // headers are dropped except the inputs the route declares (Authorization, Cookie, Camel*, spoofed ids, ...)
        from("direct:order-create").routeId("order-create")
                .process(callerIdentity)
                .removeHeaders("*", IDEMPOTENCY_KEY)
                .throttle(throttleMaxRequests).totalRequestsMode().timePeriodMillis(throttlePeriod.toMillis())
                .correlationExpression(exchangeProperty(CallerIdentity.PARTNER_ID)).rejectExecution(true)
                .validate(header(IDEMPOTENCY_KEY).regex("[A-Za-z0-9_-]{8,64}"))
                .to("json-validator:schema/order.json")
                .unmarshal(new JacksonDataFormat(mapper, OrderRequest.class))
                // keys are scoped to the partner, so partners cannot collide with or probe each other's keys
                .idempotentConsumer(simple("${exchangeProperty.partnerId}:${header.Idempotency-Key}"),
                        new IdempotencyKeyRepository(entityManagerFactory, transactionStrategy))
                .skipDuplicate(false)
                .completionEager(true)
                .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE).isNull())
                .bean(orderService, "accept(${exchangeProperty.caller}, ${header.Idempotency-Key}, ${body})")
                // the MDC adds partnerId and camel.exchangeId to this log record
                .log("Order ${body.orderId} accepted")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202))
                .end()
                .end()
                // a retry gets the order of the first request, outside of the idempotent block so that a failure
                // here cannot release the key of the first request
                .filter(exchangeProperty(Exchange.DUPLICATE_MESSAGE))
                .bean(orderService, "replay(${exchangeProperty.caller}, ${header.Idempotency-Key})")
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200))
                .end()
                .marshal(json);

        from("direct:order-read").routeId("order-read")
                .process(callerIdentity)
                .removeHeaders("*", "id")
                .bean(orderService, "read(${exchangeProperty.caller}, ${header.id})")
                .marshal(json);

        from("direct:audit-query").routeId("audit-query")
                .process(callerIdentity)
                .removeHeaders("*", "actor", "action", "limit")
                .validate(simple("${header.limit} == null || ${header.limit} regex '[1-9][0-9]{0,2}'"))
                .bean(auditLog, "query(${exchangeProperty.caller}, ${header.actor}, ${header.action}, ${header.limit})")
                .marshal(json);

        from("direct:audit-verify").routeId("audit-verify")
                .process(callerIdentity)
                .removeHeaders("*")
                .bean(auditLog, "verify(${exchangeProperty.caller})")
                .marshal(json);

        // The chain head written to the logs is an anchor: once shipped to a system the database administrators
        // cannot change, a rewritten chain no longer matches it
        from("timer:audit-anchor?period=" + anchorPeriod.toMillis()).routeId("audit-anchor")
                .bean(auditLog, "head")
                .log("Audit chain head: ${body}");
    }
}
