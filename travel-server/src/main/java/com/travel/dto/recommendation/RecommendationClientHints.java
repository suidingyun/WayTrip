package com.travel.dto.recommendation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 客户端随推荐请求传入的上下文（位置等），用于排序加权。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationClientHints {

    public static final RecommendationClientHints EMPTY = new RecommendationClientHints(null, null, null);

    /**
     * 与 spot.region_id 对应的区域 id（若前端能解析出省市可选用）
     */
    private Long regionId;

    private BigDecimal latitude;

    private BigDecimal longitude;

    public static RecommendationClientHints empty() {
        return EMPTY;
    }
}
