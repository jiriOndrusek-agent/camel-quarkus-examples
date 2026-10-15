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

import java.util.Map;

import io.quarkus.arc.Unremovable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.NamedNode;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.Route;
import org.apache.camel.component.ai.tool.AiToolAuthorizationDeniedEvent;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.RoutePolicy;
import org.apache.camel.spi.RoutePolicyFactory;
import org.apache.camel.support.EventNotifierSupport;
import org.apache.camel.support.RoutePolicySupport;

/**
 * Records every call of an ai-tool route in the {@code tool_audit} table: the tool, who called it and how it ended,
 * {@code OK}, {@code FAILED} or {@code DENIED}. The arguments are not recorded, they may contain personal data.
 * <p>
 * A route policy sees each run of a tool route as it completes, whether the call comes from the chat agent or from the
 * MCP server. A call denied by the authorization policy of the tool never runs the route: the
 * {@link AiToolAuthorizationDeniedEvent} tells about it instead.
 */
@Singleton
// Camel looks the route policy factory and the event notifier up by type, which Quarkus cannot see when removing
// unused beans
@Unremovable
public class ToolAudit extends EventNotifierSupport implements RoutePolicyFactory {

    private static final String INSERT_AUDIT = "sql:INSERT INTO tool_audit (tool, caller, outcome) VALUES (:#tool, :#caller, :#outcome)";

    @Inject
    ProducerTemplate producerTemplate;

    public ToolAudit() {
        // Only the denial events matter, so Camel does not need to create the events of every exchange for this notifier
        setIgnoreExchangeEvents(true);
        setIgnoreStepEvents(true);
    }

    @Override
    public RoutePolicy createRoutePolicy(CamelContext camelContext, String routeId, NamedNode route) {
        if (!((RouteDefinition) route).getInput().getEndpointUri().startsWith("ai-tool:")) {
            return null;
        }
        return new RoutePolicySupport() {
            @Override
            public void onExchangeDone(Route route, Exchange exchange) {
                record(route.getRouteId(), exchange, exchange.isFailed() ? "FAILED" : "OK");
            }
        };
    }

    @Override
    public boolean isEnabled(CamelEvent event) {
        return event instanceof AiToolAuthorizationDeniedEvent;
    }

    @Override
    public void notify(CamelEvent event) {
        AiToolAuthorizationDeniedEvent denied = (AiToolAuthorizationDeniedEvent) event;
        record(denied.getToolName(), denied.getExchange(), "DENIED");
    }

    private void record(String tool, Exchange exchange, String outcome) {
        producerTemplate.sendBodyAndHeaders(INSERT_AUDIT, null, Map.of(
                "tool", tool,
                // The customer tools run for the customer of the chat. The MCP server does not hand the identity of its
                // caller to the routes yet (CAMEL-25406), so those calls are recorded as "mcp".
                "caller", exchange.getProperty(ChatResource.CUSTOMER_ID, "mcp", String.class),
                "outcome", outcome));
    }
}
