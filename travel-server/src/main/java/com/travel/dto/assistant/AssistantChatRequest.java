package com.travel.dto.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 旅游助手对话请求。
 */
@Data
public class AssistantChatRequest {

    @NotBlank(message = "消息不能为空")
    @Size(max = 4000, message = "消息过长")
    private String message;

    /** 前端生成的会话 ID，用于多轮记忆 */
    @Size(max = 64)
    private String chatId;

    /** 当前浏览的景点 ID（可选） */
    private Long spotId;

    /** 地区/省份提示（可选，辅助 RAG 与回答） */
    @Size(max = 64)
    private String province;
}
