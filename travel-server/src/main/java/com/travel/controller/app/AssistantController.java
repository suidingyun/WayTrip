package com.travel.controller.app;

import com.travel.assistant.TravelAssistantService;
import com.travel.common.result.ApiResponse;
import com.travel.dto.assistant.AssistantChatRequest;
import com.travel.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 旅游 AI 助手（需登录，与现有 JWT 拦截一致）。
 */
@Tag(name = "旅游助手", description = "Spring AI + 可选 RAG")
@RestController
@RequestMapping("/api/v1/assistant")
@Profile("!noai")
@RequiredArgsConstructor
public class AssistantController {

    private final TravelAssistantService travelAssistantService;

    @Operation(summary = "多轮对话")
    @PostMapping("/chat")
    public ApiResponse<Map<String, String>> chat(@Valid @RequestBody AssistantChatRequest request) {
        Long userId = UserContext.getUserId();
        String reply = travelAssistantService.chat(
                request.getMessage(),
                request.getChatId(),
                userId,
                request.getSpotId(),
                request.getProvince());
        return ApiResponse.success(Map.of("reply", reply));
    }
}
