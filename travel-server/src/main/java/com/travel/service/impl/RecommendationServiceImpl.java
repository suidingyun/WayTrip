package com.travel.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travel.dto.home.HotSpotResponse;
import com.travel.dto.recommendation.CachedRecItem;
import com.travel.dto.recommendation.RecommendationCachePayload;
import com.travel.dto.recommendation.RecommendationResponse;
import com.travel.entity.*;
import com.travel.enums.OrderStatus;
import com.travel.mapper.*;
import com.travel.service.RecommendationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private final SpotMapper spotMapper;
    private final ReviewMapper reviewMapper;
    private final UserSpotFavoriteMapper userSpotFavoriteMapper;
    private final OrderMapper orderMapper;
    private final SpotCategoryMapper categoryMapper;
    private final SpotRegionMapper spotRegionMapper;
    private final UserMapper userMapper;
    private final UserPreferenceMapper userPreferenceMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String SIMILARITY_KEY = "recommendation:similarity:";
    private static final String USER_REC_KEY = "recommendation:user:";
    private static final int MIN_RATINGS_FOR_CF = 3;

    /** 混合：协同过滤权重（约 4:2） */
    private static final double HYBRID_CF_WEIGHT = 4.0 / 6.0;
    // limit表示最多返回多少条推荐列表
    @Override
    public RecommendationResponse getRecommendations(Long userId, Integer limit) {
        if (limit == null || limit <= 0) limit = 10;

        // 未登录：与首页「热门推荐」一致走冷启动时令+热度（原逻辑在拦截器下会因无 Token 失败，前端 catch 后列表为空）
        if (userId == null) {
            return handleColdStart(null, limit);
        }

        String cacheKey = USER_REC_KEY + userId;
        Object cached = redisTemplate.opsForValue().get(cacheKey);

        if (cached instanceof RecommendationCachePayload payload
                && payload.getItems() != null
                && !payload.getItems().isEmpty()) {
            return buildFromCachePayload(payload, limit); // 从redis缓存中获取推荐列表
        }
       
        if (cached instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Long) {
            redisTemplate.delete(cacheKey);
        } 
        // 如果缓存中没有推荐列表，则重新计算推荐列表
        return computeRecommendations(userId, limit);
    }

    @Override
    public RecommendationResponse refreshRecommendations(Long userId, Integer limit) {
        if (limit == null || limit <= 0) limit = 10;
        if (userId == null) {
            return handleColdStart(null, limit);
        }
        redisTemplate.delete(USER_REC_KEY + userId);
        return computeRecommendations(userId, limit);
    }

    private RecommendationResponse computeRecommendations(Long userId, Integer limit) {
        Long ratingCount = reviewMapper.selectCount(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getUserId, userId)
                        .eq(Review::getIsDeleted, 0)
        );

        if (ratingCount < MIN_RATINGS_FOR_CF) {
            RecommendationResponse content = tryContentOnlyRecommendations(userId, limit);
            if (content != null && content.getList() != null && !content.getList().isEmpty()) {
                cachePersonalizedList(userId, content.getList(), limit);
                return content;
            }
            return handleColdStart(userId, limit);
        }

        CfComputeResult cfResult = computeItemCfScoresAndSeeds(userId);
        if (cfResult.scores.isEmpty()) {
            RecommendationResponse content = tryContentOnlyRecommendations(userId, limit);
            if (content != null && content.getList() != null && !content.getList().isEmpty()) {
                cachePersonalizedList(userId, content.getList(), limit);
                return content;
            }
            return handleColdStart(userId, limit);
        }

        Set<String> profile = buildUserTagProfile(userId);
        List<HybridScoredSpot> ranked = rankHybridAndExplain(cfResult, profile);

        List<Long> orderedIds = ranked.stream().map(HybridScoredSpot::getSpotId).collect(Collectors.toList());
        List<Long> filteredIds = filterInteractedSpots(userId, orderedIds);
        List<HybridScoredSpot> filtered = ranked.stream()
                .filter(h -> filteredIds.contains(h.getSpotId()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            RecommendationResponse content = tryContentOnlyRecommendations(userId, limit);
            if (content != null && content.getList() != null && !content.getList().isEmpty()) {
                cachePersonalizedList(userId, content.getList(), limit);
                return content;
            }
            return handleColdStart(userId, limit);
        }

        RecommendationResponse hybridResp = buildHybridResponse(filtered.stream().limit(limit).collect(Collectors.toList()));
        cachePersonalizedList(userId, hybridResp.getList(), limit);
        return hybridResp;
    }

    /**
     * 评价不足或 CF 无结果时：仅凭用户画像（收藏/订单/已评景点标签）做内容匹配，保证有可解释文案
     */
    private RecommendationResponse tryContentOnlyRecommendations(Long userId, Integer limit) {
        Set<String> profile = buildUserTagProfile(userId);
        if (profile.isEmpty()) {
            return null;
        }
        Set<Long> exclude = getInteractedSpotIds(userId);

        List<Spot> candidates = spotMapper.selectList(
                new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getIsPublished, 1)
                        .eq(Spot::getIsDeleted, 0)
                        .orderByDesc(Spot::getHeatScore)
                        .last("LIMIT 400"));

        List<HybridScoredSpot> rows = new ArrayList<>();
        for (Spot spot : candidates) {
            if (exclude.contains(spot.getId())) {
                continue;
            }
            Set<String> spotTokens = tokenizeSpot(spot);
            double j = jaccard(profile, spotTokens);
            if (j <= 0) {
                continue;
            }
            Set<String> inter = new HashSet<>(profile);
            inter.retainAll(spotTokens);
            List<String> topTags = inter.stream().sorted().limit(3).collect(Collectors.toList());

            HybridScoredSpot h = new HybridScoredSpot();
            h.setSpotId(spot.getId());
            h.setHybridScore(j);
            h.setCfNorm(0);
            h.setContentScore(j);
            h.setReason(buildContentOnlyReason(topTags));
            rows.add(h);
        }

        Map<Long, Integer> heatById = candidates.stream()
                .collect(Collectors.toMap(Spot::getId, s -> s.getHeatScore() != null ? s.getHeatScore() : 0, (a, b) -> a));
        rows.sort(Comparator.comparingDouble(HybridScoredSpot::getHybridScore).reversed()
                .thenComparingInt(h -> heatById.getOrDefault(h.getSpotId(), 0)));

        if (rows.isEmpty()) {
            return null;
        }

        return buildHybridResponse(rows.stream().limit(limit).collect(Collectors.toList()));
    }

    private String buildContentOnlyReason(List<String> topTags) {
        if (topTags == null || topTags.isEmpty()) {
            return "根据您去过的景点和收藏，猜您可能会喜欢这里。";
        }
        String tagStr = String.join("、", topTags);
        return String.format("您常留意「%s」这类元素，这里和您最近的口味比较合拍。", tagStr);
    }

    private void cachePersonalizedList(Long userId, List<RecommendationResponse.SpotItem> list, int limit) {
        if (list == null || list.isEmpty()) {
            return;
        }
        int cap = Math.min(list.size(), Math.max(limit * 3, limit));
        List<CachedRecItem> cacheItems = list.stream()
                .limit(cap)
                .map(i -> new CachedRecItem(i.getId(), i.getReason(), i.getScore()))
                .collect(Collectors.toList());
        redisTemplate.opsForValue().set(USER_REC_KEY + userId, new RecommendationCachePayload(cacheItems), 1, TimeUnit.HOURS);
    }

    private CfComputeResult computeItemCfScoresAndSeeds(Long userId) {
        List<Review> userRatings = reviewMapper.selectList(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getUserId, userId)
                        .eq(Review::getIsDeleted, 0)
        );
        if (userRatings.isEmpty()) {
            return CfComputeResult.empty();
        }

        Map<Long, Integer> userRatingMap = userRatings.stream()
                .collect(Collectors.toMap(Review::getSpotId, Review::getScore, (a, b) -> a));

        Map<Long, Double> scores = new HashMap<>();
        Map<Long, CfSeed> bestSeedByCandidate = new HashMap<>();

        for (Map.Entry<Long, Integer> entry : userRatingMap.entrySet()) {
            Long seedSpotId = entry.getKey();
            int rating = entry.getValue();
            Map<Long, Double> similarities = getSimilarSpots(seedSpotId);

            for (Map.Entry<Long, Double> simEntry : similarities.entrySet()) {
                Long candidateId = simEntry.getKey();
                double similarity = simEntry.getValue();
                if (userRatingMap.containsKey(candidateId)) {
                    continue;
                }
                double contrib = similarity * rating;
                scores.merge(candidateId, contrib, Double::sum);
                bestSeedByCandidate.merge(candidateId, new CfSeed(seedSpotId, contrib),
                        (old, neu) -> neu.contrib >= old.contrib ? neu : old);
            }
        }
        return new CfComputeResult(scores, bestSeedByCandidate);
    }

    private Set<String> buildUserTagProfile(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> sourceSpotIds = new LinkedHashSet<>();

        reviewMapper.selectList(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getUserId, userId)
                        .eq(Review::getIsDeleted, 0)
        ).forEach(r -> sourceSpotIds.add(r.getSpotId()));

        userSpotFavoriteMapper.selectList(
                new LambdaQueryWrapper<UserSpotFavorite>()
                        .eq(UserSpotFavorite::getUserId, userId)
                        .eq(UserSpotFavorite::getIsDeleted, 0)
        ).forEach(f -> sourceSpotIds.add(f.getSpotId()));

        orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getIsDeleted, 0)
                        .ne(Order::getStatus, OrderStatus.CANCELLED.getCode())
        ).forEach(o -> sourceSpotIds.add(o.getSpotId()));

        if (sourceSpotIds.isEmpty()) {
            return Collections.emptySet();
        }

        List<Spot> spots = spotMapper.selectBatchIds(new ArrayList<>(sourceSpotIds));
        Set<String> profile = new LinkedHashSet<>();
        for (Spot s : spots) {
            if (s != null && s.getIsDeleted() != null && s.getIsDeleted() == 0) {
                profile.addAll(tokenizeSpot(s));
            }
        }
        return profile;
    }

    private Set<String> tokenizeSpot(Spot spot) {
        Set<String> out = new LinkedHashSet<>();
        addCommaTokens(out, spot.getTags());
        addCommaTokens(out, spot.getCrowdTags());
        addCommaTokens(out, spot.getHolidayTags());
        addCommaTokens(out, spot.getTimeTags());
        addCommaTokens(out, spot.getBestSeason());
        return out;
    }

    private void addCommaTokens(Set<String> target, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String p : raw.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                target.add(t);
            }
        }
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private Map<Long, Double> minMaxNormalize(Map<Long, Double> raw) {
        if (raw.isEmpty()) {
            return raw;
        }
        double min = Collections.min(raw.values());
        double max = Collections.max(raw.values());
        if (max <= min) {
            return raw.keySet().stream()
                    .collect(Collectors.toMap(k -> k, k -> 0.5, (x, y) -> x, LinkedHashMap::new));
        }
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> e : raw.entrySet()) {
            out.put(e.getKey(), (e.getValue() - min) / (max - min));
        }
        return out;
    }

    private List<HybridScoredSpot> rankHybridAndExplain(CfComputeResult cf, Set<String> profile) {
        Map<Long, Double> cfNorm = minMaxNormalize(cf.scores);
        Set<Long> ids = cf.scores.keySet();
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }

        List<Spot> spots = spotMapper.selectBatchIds(new ArrayList<>(ids));
        Map<Long, Spot> spotMap = spots.stream()
                .filter(s -> s.getIsDeleted() != null && s.getIsDeleted() == 0
                        && s.getIsPublished() != null && s.getIsPublished() == 1)
                .collect(Collectors.toMap(Spot::getId, s -> s, (a, b) -> a));

        Set<Long> seedIds = cf.bestSeedByCandidate.values().stream()
                .map(s -> s.seedSpotId)
                .collect(Collectors.toSet());
        Map<Long, String> seedNames = new HashMap<>();
        if (!seedIds.isEmpty()) {
            for (Spot s : spotMapper.selectBatchIds(new ArrayList<>(seedIds))) {
                if (s != null) {
                    seedNames.put(s.getId(), s.getName());
                }
            }
        }

        List<HybridScoredSpot> list = new ArrayList<>();
        for (Long spotId : ids) {
            Spot spot = spotMap.get(spotId);
            if (spot == null) {
                continue;
            }
            double cfN = cfNorm.getOrDefault(spotId, 0.0);
            Set<String> spotTokens = tokenizeSpot(spot);
            double content = jaccard(profile, spotTokens);
            double hybrid = HYBRID_CF_WEIGHT * cfN + (1.0 - HYBRID_CF_WEIGHT) * content;

            CfSeed seedInfo = cf.bestSeedByCandidate.get(spotId);
            String seedName = seedInfo != null ? seedNames.get(seedInfo.seedSpotId) : null;

            Set<String> inter = new HashSet<>(profile);
            inter.retainAll(spotTokens);
            List<String> topTags = inter.stream().sorted().limit(3).collect(Collectors.toList());

            HybridScoredSpot h = new HybridScoredSpot();
            h.setSpotId(spotId);
            h.setHybridScore(hybrid);
            h.setCfNorm(cfN);
            h.setContentScore(content);
            h.setReason(buildHybridReason(seedName, topTags));
            list.add(h);
        }

        list.sort(Comparator.comparingDouble(HybridScoredSpot::getHybridScore).reversed());
        return list;
    }

    private String buildHybridReason(String seedSpotName, List<String> overlapTags) {
        boolean hasSeed = seedSpotName != null && !seedSpotName.isEmpty();
        boolean hasTags = overlapTags != null && !overlapTags.isEmpty();
        String tagStr = hasTags ? String.join("、", overlapTags) : "";

        if (hasSeed && hasTags) {
            return String.format("您给过「%s」好评，这里和那儿风格接近；另外也带着您喜欢的「%s」味道。", seedSpotName, tagStr);
        }
        if (hasSeed) {
            return String.format("您曾喜欢「%s」，不少朋友也会顺路看看同气质的地方。", seedSpotName);
        }
        if (hasTags) {
            return String.format("这里有您偏好的「%s」，和您收藏或去过的景点气质相近。", tagStr);
        }
        return "结合您以前的评价和浏览，觉得这里值得一试。";
    }

    private RecommendationResponse buildHybridResponse(List<HybridScoredSpot> rows) {
        if (rows.isEmpty()) {
            RecommendationResponse response = new RecommendationResponse();
            response.setType("personalized");
            response.setNeedPreference(false);
            response.setList(Collections.emptyList());
            return response;
        }

        List<Long> ids = rows.stream().map(HybridScoredSpot::getSpotId).collect(Collectors.toList());
        List<Spot> spots = spotMapper.selectBatchIds(ids);
        Map<Long, Spot> spotMap = spots.stream().collect(Collectors.toMap(Spot::getId, s -> s));
        Map<Long, String> categoryMap = getCategoryMap();
        Map<Long, String> regionMap = getRegionMap();

        RecommendationResponse response = new RecommendationResponse();
        response.setType("personalized");
        response.setNeedPreference(false);
        response.setList(rows.stream()
                .map(r -> {
                    Spot spot = spotMap.get(r.getSpotId());
                    if (spot == null || spot.getIsDeleted() != 0 || spot.getIsPublished() != 1) {
                        return null;
                    }
                    RecommendationResponse.SpotItem item = new RecommendationResponse.SpotItem();
                    item.setId(spot.getId());
                    item.setName(spot.getName());
                    item.setCoverImage(spot.getCoverImageUrl());
                    item.setPrice(spot.getPrice());
                    item.setAvgRating(spot.getAvgRating());
                    item.setRatingCount(spot.getRatingCount());
                    item.setCategoryName(categoryMap.get(spot.getCategoryId()));
                    item.setRegionName(regionMap.get(spot.getRegionId()));
                    item.setReason(r.getReason());
                    item.setScore(r.getHybridScore());
                    item.setTags(parseTags(spot.getTags()));
                    return item;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        return response;
    }

    private RecommendationResponse buildFromCachePayload(RecommendationCachePayload payload, int limit) {
        List<CachedRecItem> items = payload.getItems().stream().limit(limit).collect(Collectors.toList());
        if (items.isEmpty()) {
            RecommendationResponse response = new RecommendationResponse();
            response.setType("personalized");
            response.setNeedPreference(false);
            response.setList(Collections.emptyList());
            return response;
        }

        List<Long> ids = items.stream().map(CachedRecItem::getSpotId).collect(Collectors.toList());
        List<Spot> spots = spotMapper.selectBatchIds(ids);
        Map<Long, Spot> spotMap = spots.stream().collect(Collectors.toMap(Spot::getId, s -> s));
        Map<Long, String> categoryMap = getCategoryMap();
        Map<Long, String> regionMap = getRegionMap();

        RecommendationResponse response = new RecommendationResponse();
        response.setType("personalized");
        response.setNeedPreference(false);
        response.setList(items.stream()
                .map(c -> {
                    Spot spot = spotMap.get(c.getSpotId());
                    if (spot == null || spot.getIsDeleted() != 0 || spot.getIsPublished() != 1) {
                        return null;
                    }
                    RecommendationResponse.SpotItem item = new RecommendationResponse.SpotItem();
                    item.setId(spot.getId());
                    item.setName(spot.getName());
                    item.setCoverImage(spot.getCoverImageUrl());
                    item.setPrice(spot.getPrice());
                    item.setAvgRating(spot.getAvgRating());
                    item.setRatingCount(spot.getRatingCount());
                    item.setCategoryName(categoryMap.get(spot.getCategoryId()));
                    item.setRegionName(regionMap.get(spot.getRegionId()));
                    item.setReason(c.getReason());
                    item.setScore(c.getScore());
                    item.setTags(parseTags(spot.getTags()));
                    return item;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
        return response;
    }

    /**
     * 与 {@link com.travel.service.impl.AuthServiceImpl#getUserInfo}、setPreferences 一致：
     * 从 user_preference 表读标签名，再映射为 spot_category.id（与前端传类目名称一致）
     */
    private List<Long> resolvePreferredCategoryIds(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        List<UserPreference> prefs = userPreferenceMapper.selectList(
                new LambdaQueryWrapper<UserPreference>()
                        .eq(UserPreference::getUserId, userId)
                        .eq(UserPreference::getIsDeleted, 0)
        );
        if (prefs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Long> nameToId = categoryMapper.selectList(
                        new LambdaQueryWrapper<SpotCategory>().eq(SpotCategory::getIsDeleted, 0))
                .stream()
                .collect(Collectors.toMap(SpotCategory::getName, SpotCategory::getId, (a, b) -> a));
        return prefs.stream()
                .map(UserPreference::getTag)
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .map(nameToId::get)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private RecommendationResponse handleColdStart(Long userId, Integer limit) {
        User user = userId == null ? null : userMapper.selectById(userId);

        List<Long> categoryIds = resolvePreferredCategoryIds(userId);
        // 兼容旧数据：user 表 preferences 字段曾为「类目 id 逗号分隔」
        if (categoryIds.isEmpty() && user != null && user.getPreferences() != null && !user.getPreferences().isBlank()) {
            try {
                categoryIds = Arrays.stream(user.getPreferences().split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .collect(Collectors.toList());
            } catch (NumberFormatException ignored) {
                categoryIds = Collections.emptyList();
            }
        }

        if (!categoryIds.isEmpty()) {
            List<Spot> spots = spotMapper.selectList(
                    new LambdaQueryWrapper<Spot>()
                            .eq(Spot::getIsPublished, 1)
                            .in(Spot::getCategoryId, categoryIds)
                            .eq(Spot::getIsDeleted, 0)
                            .orderByDesc(Spot::getHeatScore)
                            .last("LIMIT " + limit)
            );

            Map<Long, String> categoryMap = getCategoryMap();
            Map<Long, String> regionMap = getRegionMap();
            String catLabel = categoryIds.stream()
                    .map(categoryMap::get)
                    .filter(Objects::nonNull)
                    .limit(3)
                    .collect(Collectors.joining("、"));
            final String prefReason = catLabel.isEmpty()
                    ? "按您在个人中心里保存的出行偏好，结合大家常去的热度，为您挑了这些地方。"
                    : String.format("您偏爱「%s」这类玩法，下面这些地方最近很受欢迎，也许合您心意。", catLabel);

            RecommendationResponse response = new RecommendationResponse();
            response.setType("preference");
            response.setNeedPreference(false);
            response.setList(spots.stream()
                    .map(item -> {
                        RecommendationResponse.SpotItem spotItem = new RecommendationResponse.SpotItem();
                        spotItem.setId(item.getId());
                        spotItem.setName(item.getName());
                        spotItem.setCoverImage(item.getCoverImageUrl());
                        spotItem.setPrice(item.getPrice());
                        spotItem.setAvgRating(item.getAvgRating());
                        spotItem.setRatingCount(item.getRatingCount());
                        spotItem.setCategoryName(categoryMap.get(item.getCategoryId()));
                        spotItem.setRegionName(regionMap.get(item.getRegionId()));
                        spotItem.setReason(prefReason);
                        spotItem.setTags(parseTags(item.getTags()));
                        return spotItem;
                    })
                    .collect(Collectors.toList()));
            return response;
        }

        int currentMonth = java.time.LocalDate.now().getMonthValue();
        String currentMonthStr = String.valueOf(currentMonth);

        Map<Long, String> categoryMap = getCategoryMap();
        Map<Long, String> regionMap = getRegionMap();

        List<Spot> spots = spotMapper.selectList(
                new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getIsPublished, 1)
                        .eq(Spot::getIsDeleted, 0)
                        .and(w -> w.like(Spot::getBestSeason, currentMonthStr)
                                .or().like(Spot::getBestSeason, monthToSeasonKeyword(currentMonth))
                                .or().isNull(Spot::getBestSeason)
                                .or().eq(Spot::getBestSeason, ""))
                        .orderByDesc(Spot::getHeatScore)
                        .last("LIMIT " + limit)
        );

        if (spots.size() < limit) {
            List<Long> existingIds = spots.stream().map(Spot::getId).collect(Collectors.toList());
            List<Spot> globalHot = spotMapper.selectList(
                    new LambdaQueryWrapper<Spot>()
                            .eq(Spot::getIsPublished, 1)
                            .eq(Spot::getIsDeleted, 0)
                            .notIn(!existingIds.isEmpty(), Spot::getId, existingIds)
                            .orderByDesc(Spot::getHeatScore)
                            .last("LIMIT " + (limit - spots.size()))
            );
            spots.addAll(globalHot);
        }

        RecommendationResponse response = new RecommendationResponse();
        response.setType("hot");
        response.setNeedPreference(true);
        response.setList(spots.stream()
                .map(item -> {
                    RecommendationResponse.SpotItem spotItem = new RecommendationResponse.SpotItem();
                    spotItem.setId(item.getId());
                    spotItem.setName(item.getName());
                    spotItem.setCoverImage(item.getCoverImageUrl());
                    spotItem.setPrice(item.getPrice());
                    spotItem.setAvgRating(item.getAvgRating());
                    spotItem.setRatingCount(item.getRatingCount());
                    spotItem.setCategoryName(categoryMap.get(item.getCategoryId()));
                    spotItem.setRegionName(regionMap.get(item.getRegionId()));
                    spotItem.setReason(resolveHotReason(item.getBestSeason(), currentMonth));
                    spotItem.setTags(parseTags(item.getTags()));
                    return spotItem;
                })
                .collect(Collectors.toList()));
        return response;
    }

    private String monthToSeasonKeyword(int month) {
        if (month >= 3 && month <= 5) return "春";
        if (month >= 6 && month <= 8) return "夏";
        if (month >= 9 && month <= 11) return "秋";
        return "冬";
    }

    private String resolveHotReason(String bestSeason, int currentMonth) {
        if (bestSeason == null || bestSeason.isEmpty()) {
            return "平台上大家最近在逛的热门去处，给没想好去哪的你做个参考。";
        }
        List<String> parts = Arrays.asList(bestSeason.split(","));
        String m = String.valueOf(currentMonth);
        String seasonKw = monthToSeasonKeyword(currentMonth);
        if (parts.contains(m) || parts.contains(seasonKw)) {
            return "这个时节正适合去这类地方，按人气给您排了个序，点进去可以慢慢挑。";
        }
        return "热门榜里的好去处，很多游客会顺路打卡；多评价、多收藏后，推荐会更懂您。";
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isEmpty()) return Collections.emptyList();
        return Arrays.asList(tags.split(","));
    }

    @SuppressWarnings("unchecked")
    private Map<Long, Double> getSimilarSpots(Long spotId) {
        String key = SIMILARITY_KEY + spotId;
        Map<Long, Double> similarities = (Map<Long, Double>) redisTemplate.opsForValue().get(key);
        return similarities != null ? similarities : Collections.emptyMap();
    }

    private Set<Long> getInteractedSpotIds(Long userId) {
        if (userId == null) {
            return Collections.emptySet();
        }
        Set<Long> ratedIds = reviewMapper.selectList(
                new LambdaQueryWrapper<Review>()
                        .eq(Review::getUserId, userId)
                        .eq(Review::getIsDeleted, 0)
        ).stream().map(Review::getSpotId).collect(Collectors.toSet());

        Set<Long> favoriteIds = userSpotFavoriteMapper.selectList(
                new LambdaQueryWrapper<UserSpotFavorite>()
                        .eq(UserSpotFavorite::getUserId, userId)
                        .eq(UserSpotFavorite::getIsDeleted, 0)
        ).stream().map(UserSpotFavorite::getSpotId).collect(Collectors.toSet());

        Set<Long> orderedIds = orderMapper.selectList(
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getUserId, userId)
                        .eq(Order::getIsDeleted, 0)
                        .ne(Order::getStatus, OrderStatus.CANCELLED.getCode())
        ).stream().map(Order::getSpotId).collect(Collectors.toSet());

        Set<Long> excludeIds = new HashSet<>();
        excludeIds.addAll(ratedIds);
        excludeIds.addAll(favoriteIds);
        excludeIds.addAll(orderedIds);
        return excludeIds;
    }

    private List<Long> filterInteractedSpots(Long userId, List<Long> spotIds) {
        if (spotIds.isEmpty()) return spotIds;
        Set<Long> excludeIds = getInteractedSpotIds(userId);
        return spotIds.stream().filter(id -> !excludeIds.contains(id)).collect(Collectors.toList());
    }

    @Override
    public HotSpotResponse getHotSpots(Integer limit) {
        if (limit == null || limit <= 0) limit = 10;

        List<Spot> spots = spotMapper.selectList(
                new LambdaQueryWrapper<Spot>()
                        .eq(Spot::getIsPublished, 1)
                        .eq(Spot::getIsDeleted, 0)
                        .orderByDesc(Spot::getHeatScore)
                        .last("LIMIT " + limit)
        );

        Map<Long, String> categoryMap = getCategoryMap();

        HotSpotResponse response = new HotSpotResponse();
        response.setList(spots.stream().map(spot -> {
            HotSpotResponse.SpotItem item = new HotSpotResponse.SpotItem();
            item.setId(spot.getId());
            item.setName(spot.getName());
            item.setCoverImage(spot.getCoverImageUrl());
            item.setPrice(spot.getPrice());
            item.setAvgRating(spot.getAvgRating());
            item.setHeatScore(spot.getHeatScore());
            item.setCategoryName(categoryMap.get(spot.getCategoryId()));
            return item;
        }).collect(Collectors.toList()));

        return response;
    }

    @Override
    public void updateSimilarityMatrix() {
        log.info("开始更新物品相似度矩阵...");

        List<Review> allRatings = reviewMapper.selectList(
                new LambdaQueryWrapper<Review>().eq(Review::getIsDeleted, 0)
        );

        if (allRatings.isEmpty()) {
            log.info("无评分数据，跳过相似度计算");
            return;
        }

        Map<Long, Map<Long, Integer>> userItemMatrix = new HashMap<>();
        Set<Long> allSpotIds = new HashSet<>();

        for (Review rating : allRatings) {
            userItemMatrix
                    .computeIfAbsent(rating.getUserId(), k -> new HashMap<>())
                    .put(rating.getSpotId(), rating.getScore());
            allSpotIds.add(rating.getSpotId());
        }

        List<Long> spotIdList = new ArrayList<>(allSpotIds);

        for (int i = 0; i < spotIdList.size(); i++) {
            Long spotI = spotIdList.get(i);
            Map<Long, Double> similarities = new HashMap<>();

            for (int j = 0; j < spotIdList.size(); j++) {
                if (i == j) continue;
                Long spotJ = spotIdList.get(j);
                double similarity = computeCosineSimilarity(spotI, spotJ, userItemMatrix);
                if (similarity > 0) {
                    similarities.put(spotJ, similarity);
                }
            }

            Map<Long, Double> topSimilarities = similarities.entrySet().stream()
                    .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                    .limit(20)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new
                    ));

            String key = SIMILARITY_KEY + spotI;
            redisTemplate.opsForValue().set(key, topSimilarities, 24, TimeUnit.HOURS);
        }

        log.info("物品相似度矩阵更新完成，共处理 {} 个景点", allSpotIds.size());
    }

    private double computeCosineSimilarity(Long spotI, Long spotJ, Map<Long, Map<Long, Integer>> userItemMatrix) {
        double dotProduct = 0;
        double normI = 0;
        double normJ = 0;

        for (Map<Long, Integer> userRatings : userItemMatrix.values()) {
            Integer ratingI = userRatings.get(spotI);
            Integer ratingJ = userRatings.get(spotJ);

            if (ratingI != null) {
                normI += ratingI * ratingI;
                if (ratingJ != null) {
                    dotProduct += ratingI * ratingJ;
                }
            }
            if (ratingJ != null) {
                normJ += ratingJ * ratingJ;
            }
        }

        if (normI == 0 || normJ == 0) return 0;
        return dotProduct / (Math.sqrt(normI) * Math.sqrt(normJ));
    }

    private Map<Long, String> getCategoryMap() {
        return categoryMapper.selectList(new LambdaQueryWrapper<SpotCategory>().eq(SpotCategory::getIsDeleted, 0)).stream()
                .collect(Collectors.toMap(SpotCategory::getId, SpotCategory::getName));
    }

    private Map<Long, String> getRegionMap() {
        return spotRegionMapper.selectList(new LambdaQueryWrapper<SpotRegion>().eq(SpotRegion::getIsDeleted, 0)).stream()
                .collect(Collectors.toMap(SpotRegion::getId, SpotRegion::getName));
    }

    private static final class CfSeed {
        final long seedSpotId;
        final double contrib;

        CfSeed(long seedSpotId, double contrib) {
            this.seedSpotId = seedSpotId;
            this.contrib = contrib;
        }
    }

    private static final class CfComputeResult {
        final Map<Long, Double> scores;
        final Map<Long, CfSeed> bestSeedByCandidate;

        CfComputeResult(Map<Long, Double> scores, Map<Long, CfSeed> bestSeedByCandidate) {
            this.scores = scores;
            this.bestSeedByCandidate = bestSeedByCandidate;
        }

        static CfComputeResult empty() {
            return new CfComputeResult(Collections.emptyMap(), Collections.emptyMap());
        }
    }

    @Data
    private static class HybridScoredSpot {
        private Long spotId;
        private double hybridScore;
        private double cfNorm;
        private double contentScore;
        private String reason;
    }
}
