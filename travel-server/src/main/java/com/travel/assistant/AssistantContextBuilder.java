package com.travel.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.exception.BusinessException;
import com.travel.dto.recommendation.RecommendationResponse;
import com.travel.dto.spot.SpotDetailResponse;
import com.travel.service.RecommendationService;
import com.travel.service.SpotService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将 MySQL 中的事实与推荐结果注入对话，减少模型编造。
 */
@Component
@Profile("!noai")
@RequiredArgsConstructor
public class AssistantContextBuilder {

    private final SpotService spotService;
    private final RecommendationService recommendationService;
    private final ObjectMapper objectMapper;

    public String build(Long userId, Long spotId, String provinceHint) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(provinceHint)) {
            sb.append("用户当前浏览上下文·地区提示：").append(provinceHint.trim()).append("\n");
        }
        if (userId != null && spotId != null) {
            try {
                SpotDetailResponse d = spotService.getSpotDetail(spotId, userId);
                sb.append("当前页景点事实（必须优先采信）：\n")
                        .append(toJson(spotFactsMap(d)))
                        .append("\n");
            } catch (BusinessException ex) {
                sb.append("(未能加载景点 id=").append(spotId).append("：").append(ex.getMessage()).append(")\n");
            }
        }
        if (userId != null) {
            try {
                RecommendationResponse rec = recommendationService.getRecommendations(userId, 10);
                sb.append("当前用户推荐列表摘要：\n").append(toJson(recommendationSummary(rec))).append("\n");
            } catch (Exception e) {
                sb.append("(推荐列表暂不可用)\n");
            }
        }
        return sb.toString();
    }

    private Map<String, Object> spotFactsMap(SpotDetailResponse d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("name", d.getName());
        m.put("price", d.getPrice());
        m.put("openTime", d.getOpenTime());
        m.put("address", d.getAddress());
        m.put("regionName", d.getRegionName());
        m.put("categoryName", d.getCategoryName());
        m.put("bestSeason", d.getBestSeason());
        m.put("recommendedDuration", d.getRecommendedDuration());
        m.put("tags", d.getTags());
        m.put("avgRating", d.getAvgRating());
        String desc = d.getDescription();
        if (desc != null && desc.length() > 800) {
            desc = desc.substring(0, 800) + "…";
        }
        m.put("description", desc);
        return m;
    }

    private Map<String, Object> recommendationSummary(RecommendationResponse rec) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", rec.getType());
        m.put("needPreference", rec.getNeedPreference());
        if (rec.getList() == null) {
            m.put("items", List.of());
            return m;
        }
        List<Map<String, Object>> items = rec.getList().stream().map(it -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", it.getId());
            row.put("name", it.getName());
            row.put("score", it.getScore());
            row.put("reason", it.getReason());
            row.put("regionName", it.getRegionName());
            return row;
        }).collect(Collectors.toList());
        m.put("items", items);
        return m;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
