package com.travel.dto.recommendation;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐所用「时间 / 节气 / 节日」与位置说明，供前端展示与助手上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationTimingMeta {

    /** yyyy-MM-dd，服务端 Asia/Shanghai */
    private String localDate;

    /** 当前节气中文名，如「惊蛰」 */
    private String solarTerm;

    /** 下一个节气一句话 */
    private String nextSolarTermHint;

    /** 近期节日一句话，如「临近春节（约 12 天）」 */
    private String holidayHint;

    /**
     * 客户端位置参与排序时的说明；未传位置则为 null
     */
    private String locationHint;
}
