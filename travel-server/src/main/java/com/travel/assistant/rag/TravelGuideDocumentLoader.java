package com.travel.assistant.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 加载 classpath:assistant-docs/*.md 作为本地 RAG 语料（可自行替换为旅游攻略 md）。
 * 由 {@link TravelLocalVectorStoreConfig} 注册为 Bean，避免与 {@code @ConditionalOnProperty} 的装配顺序冲突。
 */
@Slf4j
public class TravelGuideDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    public TravelGuideDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:assistant-docs/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(true)
                        .withAdditionalMetadata("filename", fileName != null ? fileName : "unknown.md")
                        .withAdditionalMetadata("source", "travel-assistant-local")
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            log.error("加载 assistant-docs Markdown 失败", e);
        }
        return allDocuments;
    }
}
