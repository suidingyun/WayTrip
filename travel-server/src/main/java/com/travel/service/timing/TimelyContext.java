package com.travel.service.timing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 一次推荐请求内的时间 / 节日上下文（在 Asia/Shanghai 计算）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimelyContext {

    private LocalDate localDate;

    /** 当前节气 */
    private String solarTerm;

    /** 下一节气展示文案 */
    private String nextSolarTermLine;

    /** 临近节日展示文案 */
    private String upcomingHolidayLine;

    /**
     * 用于与景点的 holidayTags / tags 做包含匹配的短词（春节、端午 等）
     */
    private List<String> tagMatchKeywords = Collections.emptyList();

    public static TimelyContext fallback(LocalDate today) {
        return new TimelyContext(today, null, null, null, List.of());
    }
}
