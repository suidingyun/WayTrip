package com.travel.assistant.rag;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetriever;
import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import com.travel.assistant.config.TravelAssistantProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

/**
 * 灵积云端知识库 RAG（索引需在控制台提前创建并与 {@link TravelAssistantProperties#getDashscopeRagIndex()} 一致）。
 */
@Slf4j
@Configuration
@Profile("!noai")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "travel.assistant", name = "rag-cloud-enabled", havingValue = "true")
public class TravelCloudRagAdvisorConfig {

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKey;

    private final TravelAssistantProperties properties;

    @Bean
    @Qualifier("travelCloudRagAdvisor")
    public Advisor travelCloudRagAdvisor() {
        if (!StringUtils.hasText(dashScopeApiKey)) {
            throw new IllegalStateException("启用云端 RAG 时需配置 spring.ai.dashscope.api-key");
        }
        String indexName = properties.getDashscopeRagIndex();
        if (!StringUtils.hasText(indexName)) {
            throw new IllegalStateException("启用云端 RAG 时需配置 travel.assistant.dashscope-rag-index");
        }
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
        DocumentRetriever documentRetriever = new DashScopeDocumentRetriever(
                dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(indexName)
                        .build());
        log.info("云端 RAG 已启用，知识库索引: {}", indexName);
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }
}
