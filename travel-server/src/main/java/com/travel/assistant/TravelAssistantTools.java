package com.travel.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.dto.recommendation.RecommendationResponse;
import com.travel.dto.spot.SpotDetailResponse;
import com.travel.service.RecommendationService;
import com.travel.service.SpotService;
import com.travel.util.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 供大模型调用的系统工具：事实以数据库为准。
 */
@Slf4j
@Component
@Profile("!noai")
@RequiredArgsConstructor
public class TravelAssistantTools {

    private final SpotService spotService;
    private final RecommendationService recommendationService;
    private final ObjectMapper objectMapper;

    @Tool(description = "按景点ID查询平台内的准确信息：名称、门票价格、开放时间、地址、地区、类别、简介摘录等。用户追问事实时必须调用。")
    public String getSpotFactsById(long spotId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return "{\"error\":\"需要登录后才能查询景点详情\"}";
        }
        try {
            SpotDetailResponse d = spotService.getSpotDetail(spotId, userId);
            return toJson(spotLean(d));
        } catch (Exception e) {
            log.debug("getSpotFactsById failed: {}", e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    @Tool(description = "获取当前登录用户的个性化推荐景点列表（含匹配度与简短理由）。用户问「推荐去哪」「还有什么适合我」时调用。")
    public String getMyRecommendations(int limit) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return "{\"error\":\"需要登录后才能查询推荐\"}";
        }
        int lim = limit < 1 ? 1 : (Math.min(limit, 20));
        try {
            RecommendationResponse rec = recommendationService.getRecommendations(userId, lim);
            return toJson(recSummary(rec));
        } catch (Exception e) {
            log.debug("getMyRecommendations failed: {}", e.getMessage());
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private Map<String, Object> spotLean(SpotDetailResponse d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("price", d.getPrice());
        m.put("openTime", d.getOpenTime());
        m.put("address", d.getAddress());
        m.put("regionName", d.getRegionName());
        m.put("categoryName", d.getCategoryName());
        m.put("bestSeason", d.getBestSeason());
        m.put("tags", d.getTags());
        String desc = d.getDescription();
        if (desc != null && desc.length() > 600) {
            desc = desc.substring(0, 600) + "…";
        }
        m.put("description", desc);
        return m;
    }

    private Map<String, Object> recSummary(RecommendationResponse rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", rec.getType());
        m.put("needPreference", rec.getNeedPreference());
        if (rec.getList() == null) {
            m.put("items", List.of());
            return m;
        }
        m.put("items", rec.getList().stream().map(it -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", it.getId());
            row.put("name", it.getName());
            row.put("score", it.getScore());
            row.put("reason", it.getReason());
            row.put("regionName", it.getRegionName());
            return row;
        }).collect(Collectors.toList()));
        return m;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
