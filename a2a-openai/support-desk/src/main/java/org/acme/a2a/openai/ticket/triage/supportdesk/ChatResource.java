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

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.processor.ThrottlerRejectedExecutionException;

/**
 * The customer side of the support desk. The customer is the authenticated user: the routes and the tools get the
 * customer id from here, never from the request or from the model.
 */
@Path("/support")
@RolesAllowed("customer")
public class ChatResource {

    /** The exchange property holding the id of the logged-in customer. */
    public static final String CUSTOMER_ID = "customerId";

    public static final int MAX_MESSAGE_LENGTH = 2000;

    @Inject
    SecurityIdentity identity;

    @Inject
    ProducerTemplate producerTemplate;

    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public ChatReply chat(ChatMessage message) {
        if (message == null || message.message() == null || message.message().isBlank()
                || message.message().length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException("A message of at most " + MAX_MESSAGE_LENGTH + " characters is required");
        }
        return new ChatReply(request("direct:chat", message.message()));
    }

    /**
     * The returns of the customer with their status.
     */
    @GET
    @Path("/returns")
    @Produces(MediaType.APPLICATION_JSON)
    public String returns() {
        return request("direct:customer-returns", null);
    }

    private String request(String endpointUri, Object body) {
        Exchange exchange = producerTemplate.request(endpointUri, e -> {
            e.getMessage().setBody(body);
            e.setProperty(CUSTOMER_ID, identity.getPrincipal().getName());
        });
        if (exchange.getException() instanceof ThrottlerRejectedExecutionException) {
            // The customer is still waiting for the reply to a previous message
            throw new WebApplicationException(Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header("Retry-After", 5)
                    .build());
        }
        if (exchange.getException() != null) {
            throw RuntimeCamelException.wrapRuntimeCamelException(exchange.getException());
        }
        return exchange.getMessage().getBody(String.class);
    }

    public record ChatMessage(String message) {
    }

    public record ChatReply(String reply) {
    }
}
