package com.travel.assistant.config;

import com.travel.assistant.TravelAssistantTools;
import com.travel.assistant.chatmemory.FileBasedChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!noai")
public class TravelAssistantChatClientConfiguration {

    @Bean
    @Qualifier("travelChatMemory")
    public ChatMemory travelChatMemory(TravelAssistantProperties props) {
        return new FileBasedChatMemory(
                props.getChatMemoryDir(), props.getChatMemoryMaxMessages());
    }

    @Bean
    public ChatClient travelChatClient(
            ChatModel chatModel,
            @Qualifier("travelChatMemory") ChatMemory chatMemoryBean,
            TravelAssistantTools travelAssistantTools,
            @Qualifier("travelLocalRagAdvisor") ObjectProvider<Advisor> localRagAdvisor,
            @Qualifier("travelCloudRagAdvisor") ObjectProvider<Advisor> cloudRagAdvisor) {
        return TravelAssistantChatClientSupport.build(
                chatModel,
                chatMemoryBean,
                travelAssistantTools,
                localRagAdvisor.getIfAvailable(),
                cloudRagAdvisor.getIfAvailable());
    }
}
