package com.travel.assistant.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 基于本地向量库的检索增强顾问。
 */
@Configuration
@Profile("!noai")
@ConditionalOnProperty(prefix = "travel.assistant", name = "rag-local-enabled", havingValue = "true")
public class TravelLocalRagAdvisorConfig {

    @Bean
    @Qualifier("travelLocalRagAdvisor")
    @ConditionalOnBean(name = "travelLocalVectorStore")
    public Advisor travelLocalRagAdvisor(@Qualifier("travelLocalVectorStore") VectorStore travelLocalVectorStore) {
        return QuestionAnswerAdvisor.builder(travelLocalVectorStore).build();
    }
}
