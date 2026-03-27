package com.travel.assistant.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.util.List;

/**
 * 本地 SimpleVectorStore：启动时加载 assistant-docs 下 Markdown。
 */
@Slf4j
@Configuration
@Profile("!noai")
@ConditionalOnProperty(prefix = "travel.assistant", name = "rag-local-enabled", havingValue = "true")
public class TravelLocalVectorStoreConfig {

    @Bean
    public TravelGuideDocumentLoader travelGuideDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        return new TravelGuideDocumentLoader(resourcePatternResolver);
    }

    @Bean
    public VectorStore travelLocalVectorStore(EmbeddingModel embeddingModel, TravelGuideDocumentLoader documentLoader) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        log.info("本地 RAG：正在从 classpath:assistant-docs/*.md 解析片段（仅解析，尚未请求 Embedding）…");
        List<Document> docs = documentLoader.loadMarkdowns();
        if (docs.isEmpty()) {
            log.warn("本地 RAG 未加载到任何文档（请放入 src/main/resources/assistant-docs/*.md）");
        } else {
            log.warn(
                    "本地 RAG：将向量化 {} 个片段，启动阶段会多次调用灵积 Embedding，语料大时可能数分钟无新日志，属正常现象。不需要本地 RAG 请在 application.yml 设 travel.assistant.rag-local-enabled: false",
                    docs.size());
            store.add(docs);
            log.info("本地 RAG 已向量化并写入 SimpleVectorStore，共 {} 个片段", docs.size());
        }
        return store;
    }
}
