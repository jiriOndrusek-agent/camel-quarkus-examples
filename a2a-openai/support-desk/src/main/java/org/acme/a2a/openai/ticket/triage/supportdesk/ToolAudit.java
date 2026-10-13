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
package org.acme.a2a.openai.ticket.triage.supportdesk;

import java.util.Map;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.RoutePolicySupport;

/**
 * Records every call of an ai-tool route in the {@code tool_audit} table: the tool, who called it and whether it
 * succeeded. The arguments are not recorded, they may contain personal data.
 * <p>
 * A route policy sees each call of the tool route as it completes, whether the call comes from the chat agent or from
 * the MCP server.
 */
@ApplicationScoped
// Camel looks the factory up by type, which Quarkus cannot see when removing unused beans
@Unremovable
public class ToolAudit implements RoutePolicyFactory {

    private static final String INSERT_AUDIT = "sql:INSERT INTO tool_audit (tool, caller, outcome) VALUES (:#tool, :#caller, :#outcome)";

    @Inject
    ProducerTemplate producerTemplate;

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        if (!((RouteDefinition) route).getInput().getEndpointUri().startsWith("ai-tool:")) {
            return null;
        }
        return new RoutePolicySupport() {
            @Override
            public void onExchangeDone(Route route, Exchange exchange) {
                producerTemplate.sendBodyAndHeaders(INSERT_AUDIT, null, Map.of(
                        "tool", route.getRouteId(),
                        // The customer tools run for the customer of the chat. The MCP server does not hand the
                        // identity of its caller to the routes yet (CAMEL-25406), so those calls are recorded as "mcp".
                        "caller", exchange.getProperty(ChatResource.CUSTOMER_ID, "mcp", String.class),
                        "outcome", exchange.isFailed() ? "FAILED" : "OK"));
            }
        };
    }
}
