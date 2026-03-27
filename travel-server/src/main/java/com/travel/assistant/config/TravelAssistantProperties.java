package com.travel.assistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 旅游助手与 RAG 开关配置。
 */
@Data
@Component
@Profile("!noai")
@ConfigurationProperties(prefix = "travel.assistant")
public class TravelAssistantProperties {

    /**
     * Kryo 会话文件目录。
     */
    private String chatMemoryDir = "./tmp/travel-chat-memory";

    /**
     * 每个会话保留的最大消息条数（双向合计）。
     */
    private int chatMemoryMaxMessages = 32;

    /** 是否启用本地向量库（classpath / 启动加载的 md） */
    private boolean ragLocalEnabled = true;

    /** 是否启用灵积云端知识库检索 */
    private boolean ragCloudEnabled = false;

    /**
     * 云端知识库索引名（在阿里云百炼 / 灵积控制台创建后填写）。
     */
    private String dashscopeRagIndex = "waytrip-guides";
}
