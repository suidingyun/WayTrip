package com.travel.assistant;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 旅游助手对话编排：系统提示 + 数据库上下文 + RAG + 工具。
 */
@Service
@Profile("!noai")
@RequiredArgsConstructor
public class TravelAssistantService {

    private static final String SYSTEM_PROMPT = """
            你是 WayTrip 旅游助手「小途」。语气友好、简洁、实用。
            规则：
            1）涉及门票价格、开放时间、地址、评分等事实，必须以工具 getSpotFactsById 返回或对话中「当前页景点事实 / 推荐列表摘要」中的 JSON 为准，不可编造。
            2）若事实缺失请明确说「平台未收录该信息」，不要猜测。
            3）游玩路线、美食、交通tips、季节性建议可结合检索到的攻略正文发挥；若与事实冲突，以事实为准。
            4）不要向用户暴露「工具名」「RAG」等实现细节。
            """;

    private final ChatClient travelChatClient;
    private final AssistantContextBuilder contextBuilder;

    public String chat(String message, String chatId, Long userId, Long spotId, String provinceHint) {
        final String conversationId = StringUtils.hasText(chatId) ? chatId : "default";
        String dbContext = contextBuilder.build(userId, spotId, provinceHint);
        String system = SYSTEM_PROMPT;
        if (StringUtils.hasText(dbContext)) {
            system = SYSTEM_PROMPT + "\n【本轮系统上下文】\n" + dbContext;
        }
        final String systemText = system;
        ChatResponse response = travelChatClient.prompt()
                .system(systemText)
                .user(message)
                .advisors(spec -> spec.param(TravelAssistantPropertiesConstants.CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                .call()
                .chatResponse();
        return response.getResult().getOutput().getText();
    }
}
