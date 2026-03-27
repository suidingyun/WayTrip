package com.travel.assistant.config;

import com.travel.assistant.TravelAssistantTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

/**
 * Builds the Travel {@link ChatClient} (logic kept out of {@link TravelAssistantChatClientConfiguration} for clarity).
 */
final class TravelAssistantChatClientSupport {

    private static final Logger log =
            LoggerFactory.getLogger("com.travel.assistant.config.TravelAssistantChatClientSupport");

    private TravelAssistantChatClientSupport() {
    }

    static ChatClient build(
            ChatModel chatModel,
            ChatMemory chatMemoryBean,
            TravelAssistantTools travelAssistantTools,
            Advisor travelLocalRagAdvisor,
            Advisor travelCloudRagAdvisor
    ) {
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemoryBean).build();
        ChatClient.Builder builder =
                ChatClient.builder(chatModel).defaultAdvisors(memoryAdvisor);

        if (travelLocalRagAdvisor != null) {
            builder = builder.defaultAdvisors(travelLocalRagAdvisor);
            log.info("Travel Assistant: local vector RAG advisor enabled");
        }
        if (travelCloudRagAdvisor != null) {
            builder = builder.defaultAdvisors(travelCloudRagAdvisor);
            log.info("Travel Assistant: DashScope cloud RAG advisor enabled");
        }

        try {
            MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                    .toolObjects(travelAssistantTools)
                    .build();
            builder = builder.defaultToolCallbacks(provider.getToolCallbacks());
            log.info("Travel Assistant: tool callbacks registered");
        } catch (Exception e) {
            log.warn("Travel Assistant: tool callbacks failed; context only. Cause: {}", e.getMessage());
        }

        return builder.build();
    }
}
