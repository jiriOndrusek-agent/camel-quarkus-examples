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
package org.acme.partner.gateway.audit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.camel.spi.CamelEvent;

/**
 * Audits changes to what the gateway runs. Camel Quarkus bridges these Camel events to CDI because they are observed.
 */
@ApplicationScoped
public class CamelLifecycleAuditor {

    @Inject
    AuditLog auditLog;

    void onContextStarted(@Observes CamelEvent.CamelContextStartedEvent event) {
        audit("CONTEXT_STARTED", "camel-context/" + event.getContext().getName());
    }

    void onRouteStarted(@Observes CamelEvent.RouteStartedEvent event) {
        audit("ROUTE_STARTED", "route/" + event.getRoute().getRouteId());
    }

    void onRouteStopped(@Observes CamelEvent.RouteStoppedEvent event) {
        audit("ROUTE_STOPPED", "route/" + event.getRoute().getRouteId());
    }

    void onRouteReloaded(@Observes CamelEvent.RouteReloadedEvent event) {
        audit("ROUTE_RELOADED", "route/" + event.getRoute().getRouteId());
    }

    private void audit(String action, String resource) {
        auditLog.append(Caller.SYSTEM, action, resource, Outcome.SUCCESS, null);
    }
}
