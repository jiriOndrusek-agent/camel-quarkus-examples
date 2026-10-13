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

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configures the agent behind the customer chat: the chat model, a conversation memory per customer and the limits of
 * the tool calling loop. The tools are the ai-tool routes tagged {@code customer}, see {@code camel/tools.yaml}.
 */
@ApplicationScoped
public class SupportAgentConfiguration {

    /** How many rounds of tool calls the model may make to answer one customer message. */
    static final int MAX_TOOL_CALLING_ROUND_TRIPS = 5;
    /** How many messages of a conversation the model sees, tool calls and results included. */
    static final int MAX_MEMORY_MESSAGES = 20;

    // Kept in memory for the example, so the conversations are lost when the application stops
    private final ChatMemoryStore conversations = new InMemoryChatMemoryStore();

    @Produces
    @Named("supportAgentConfiguration")
    AgentConfiguration supportAgentConfiguration(
            @ConfigProperty(name = "support-desk.llm.base-url") String baseUrl,
            @ConfigProperty(name = "support-desk.llm.model") String model,
            @ConfigProperty(name = "support-desk.llm.api-key") String apiKey) {
        return new AgentConfiguration()
                .withChatModel(OpenAiChatModel.builder()
                        .baseUrl(baseUrl)
                        .modelName(model)
                        .apiKey(apiKey)
                        .build())
                .withChatMemoryProvider(customerId -> MessageWindowChatMemory.builder()
                        .id(customerId)
                        .maxMessages(MAX_MEMORY_MESSAGES)
                        .chatMemoryStore(conversations)
                        .build())
                .withMaxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUND_TRIPS)
                // The model only learns that a tool failed. The cause (an SQL error, a URL, ...) stays in the log, so
                // it cannot end up in a reply to the customer. The MCP server does the same for the support tools.
                .withToolExecutionErrorHandler((error, context) -> ToolErrorHandlerResult.text("Tool execution failed"));
    }
}
