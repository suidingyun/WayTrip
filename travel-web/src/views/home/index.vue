<template>
  <div class="home-page">
    <!-- Hero 区域 -->
    <section class="hero">
      <div class="hero-content">
        <h1 class="hero-title">发现旅途之美</h1>
        <p class="hero-subtitle">探索精选目的地，开启你的梦幻旅程</p>
        <div class="hero-search" @click="$router.push('/search')">
          <el-icon><Search /></el-icon>
          <span>搜索景点、目的地...</span>
        </div>
      </div>
      <!-- 轮播背景 -->
      <el-carousel
        class="hero-carousel"
        height="480px"
        :interval="5000"
        :autoplay="true"
        :pause-on-hover="false"
        arrow="hover"
        indicator-position="inside"
      >
        <el-carousel-item v-for="banner in banners" :key="banner.id">
          <img :src="getImageUrl(banner.imageUrl)" class="hero-bg" alt="" />
        </el-carousel-item>
      </el-carousel>
    </section>

    <div class="page-container">
      <!-- 热门目的地 -->
      <section class="section">
        <div class="section-header">
          <h2 class="section-title">🔥 热门目的地</h2>
          <el-button text type="primary" @click="$router.push('/spots')">查看全部 →</el-button>
        </div>
        <div class="hot-grid">
          <div
            v-for="spot in hotSpots"
            :key="spot.id"
            class="hot-card card"
            @click="$router.push(`/spots/${spot.id}`)"
          >
            <div class="hot-img-wrapper">
              <img :src="getImageUrl(spot.coverImage)" class="hot-img" alt="" />
              <div class="hot-overlay">
                <span class="hot-price">¥{{ spot.price }} 起</span>
              </div>
            </div>
            <div class="hot-info">
              <h3 class="hot-name">{{ spot.name }}</h3>
              <p class="hot-meta">
                <span class="star-text">★ {{ spot.avgRating || '4.5' }}</span>
                <span class="hot-region">{{ spot.regionName }}</span>
              </p>
            </div>
          </div>
        </div>
      </section>

      <!-- 个性化推荐（含冷启动热门 / 内容推荐 / 混合协同） -->
      <section class="section">
        <div class="section-header">
          <h2 class="section-title">✨ {{ recommendType }}</h2>
          <el-button text type="primary" :loading="refreshing" @click="handleRefresh">换一批</el-button>
        </div>
        <p v-if="recommendHint" class="section-hint">{{ recommendHint }}</p>

        <!-- 偏好提示 -->
        <div v-if="needPreference && userStore.isLoggedIn" class="preference-tip" @click="showPreferenceDialog = true">
          <el-icon><Setting /></el-icon>
          <span>设置偏好标签，获取更精准的推荐</span>
          <el-icon><ArrowRight /></el-icon>
        </div>

        <div class="recommend-list" v-if="recommendations.length">
          <div
            v-for="spot in recommendations"
            :key="spot.id"
            class="recommend-card card"
            @click="goRecommendSpot(spot)"
          >
            <img :src="getImageUrl(spot.coverImage)" class="rec-img" alt="" />
            <div class="rec-content">
              <div class="rec-top">
                <h3 class="rec-name">{{ spot.name }}</h3>
                <span class="star-text">★ {{ spot.avgRating || '4.5' }}</span>
              </div>
              <p class="rec-desc">{{ spot.intro || '暂无介绍，点击进入详情可查看推荐说明...' }}</p>
              <div class="rec-bottom">
                <span class="tag">{{ spot.categoryName }}</span>
                <div class="rec-bottom-right">
                  <span v-if="spot.score != null" class="rec-score">匹配度 {{ formatScore(spot.score) }}</span>
                  <span class="price">¥{{ spot.price }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无推荐" />
      </section>
    </div>

    <!-- 偏好设置弹窗 -->
    <el-dialog v-model="showPreferenceDialog" title="选择你感兴趣的类型" width="480px" :close-on-click-modal="false">
      <div class="preference-tags">
        <el-check-tag
          v-for="cat in categories"
          :key="cat.id"
          :checked="selectedCategories.includes(cat.id)"
          @change="toggleCategory(cat.id)"
        >
          {{ cat.name }}
        </el-check-tag>
      </div>
      <template #footer>
        <el-button @click="showPreferenceDialog = false">取消</el-button>
        <el-button type="primary" :loading="savingPref" @click="savePreferences">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { getBanners, getHotSpots, getRecommendations, refreshRecommendations } from '@/api/home'
import { getFilters } from '@/api/spot'
import { setPreferences, getUserInfo } from '@/api/auth'
import { getImageUrl } from '@/utils/request'
import { ElMessage } from 'element-plus'

const router = useRouter()
const userStore = useUserStore()

const REC_STORAGE_PREFIX = 'waytrip_rec_'

/** 进入详情时带上推荐理由（仅列表不展示长文案） */
function goRecommendSpot(spot) {
  if (spot.reason || spot.score != null) {
    try {
      sessionStorage.setItem(
        `${REC_STORAGE_PREFIX}${spot.id}`,
        JSON.stringify({ reason: spot.reason || '', score: spot.score ?? null })
      )
    } catch (e) { /* ignore */ }
  }
  router.push(`/spots/${spot.id}`)
}

const banners = ref([])
const hotSpots = ref([])
const recommendations = ref([])
const categories = ref([])
const selectedCategories = ref([])
const showPreferenceDialog = ref(false)
const savingPref = ref(false)
const refreshing = ref(false)
const needPreference = ref(false)
const recommendationType = ref('hot')

const recommendType = computed(() => {
  const types = {
    personalized: '智能推荐',
    preference: '偏好推荐',
    hot: '热门推荐'
  }
  return types[recommendationType.value] || '为你推荐'
})

/** 说明当前列表是「混合协同+内容」还是「冷启动」等，避免误以为只有一句时令热门 */
const recommendHint = computed(() => {
  if (recommendationType.value === 'personalized') {
    return '列表仅显示匹配度；点击进入景点详情页可查看完整推荐原因（协同/内容匹配说明）。'
  }
  if (recommendationType.value === 'preference') {
    return '已按您在资料中选择的类目偏好生成解释与排序。'
  }
  if (recommendationType.value === 'hot') {
    return '当前为冷启动热门：评价较少或标签未命中时按时令与热度推荐；进入详情可查看简要说明。'
  }
  return ''
})

const fetchBanners = async () => {
  try {
    const res = await getBanners()
    banners.value = res.data?.list || []
  } catch (e) { /* ignore */ }
}

const fetchHotSpots = async () => {
  try {
    const res = await getHotSpots(3)
    hotSpots.value = (res.data?.list || []).slice(0, 3)
  } catch (e) { /* ignore */ }
}

const formatScore = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : ''
}

const fetchRecommendations = async () => {
  try {
    const res = await getRecommendations(6)
    recommendations.value = res.data?.list || res.data || []
    needPreference.value = res.data?.needPreference || false
    recommendationType.value = res.data?.type || 'hot'
  } catch (e) { /* ignore */ }
}

const fetchCategories = async () => {
  try {
    const res = await getFilters()
    categories.value = res.data?.categories || []
  } catch (e) { /* ignore */ }
}

const handleRefresh = async () => {
  refreshing.value = true
  try {
    const res = await refreshRecommendations(6)
    recommendations.value = res.data?.list || res.data || []
    needPreference.value = res.data?.needPreference || false
    recommendationType.value = res.data?.type || 'hot'
  } catch (e) { /* ignore */ }
  refreshing.value = false
}

const toggleCategory = (id) => {
  const idx = selectedCategories.value.indexOf(id)
  if (idx > -1) {
    selectedCategories.value.splice(idx, 1)
  } else {
    selectedCategories.value.push(id)
  }
}

/** 与个人中心一致：用服务端/Store 中的偏好「名称」映射为首页多选所用的类目 id */
const syncPreferenceDialogFromStore = () => {
  const names = userStore.userInfo?.preferences
  if (!names?.length || !categories.value.length) {
    selectedCategories.value = []
    return
  }
  const ids = []
  for (const name of names) {
    const cat = categories.value.find((c) => c.name === name)
    if (cat) ids.push(cat.id)
  }
  selectedCategories.value = ids
}

watch(showPreferenceDialog, (open) => {
  if (open) syncPreferenceDialogFromStore()
})

const savePreferences = async () => {
  if (!selectedCategories.value.length) {
    ElMessage.warning('请至少选择一个标签')
    return
  }
  savingPref.value = true
  try {
    const tagNames = selectedCategories.value.map(id => {
      const cat = categories.value.find(c => c.id === id)
      return cat ? cat.name : id
    })
    await setPreferences(tagNames)
    userStore.updatePreferences(tagNames)
    showPreferenceDialog.value = false
    ElMessage.success('偏好设置成功')
    await fetchRecommendations()
  } catch (e) { /* ignore */ }
  savingPref.value = false
}

onMounted(async () => {
  fetchBanners()
  fetchHotSpots()
  await fetchCategories()
  if (userStore.isLoggedIn) {
    try {
      const res = await getUserInfo()
      if (res.data) userStore.setUserInfo(res.data)
    } catch (e) { /* ignore */ }
  }
  fetchRecommendations()
})
</script>

<style lang="scss" scoped>
/* ===== Hero ===== */
.hero {
  position: relative;
  height: 480px;
  overflow: hidden;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.hero-carousel {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
}

.hero-bg {
  width: 100%;
  height: 100%;
  object-fit: cover;
  filter: brightness(0.6);
}

.hero-content {
  position: relative;
  z-index: 2;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #fff;
  text-align: center;
}

.hero-title {
  font-size: 48px;
  font-weight: 700;
  margin-bottom: 16px;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.hero-subtitle {
  font-size: 20px;
  margin-bottom: 32px;
  opacity: 0.9;
}

.hero-search {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 32px;
  background: rgba(255, 255, 255, 0.95);
  border-radius: 28px;
  color: #909399;
  font-size: 16px;
  cursor: pointer;
  transition: all 0.3s;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);

  &:hover {
    background: #fff;
    transform: scale(1.02);
  }
}

/* ===== Section ===== */
.section {
  margin-top: 40px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.section-hint {
  font-size: 13px;
  color: #909399;
  line-height: 1.55;
  margin: -12px 0 16px;
  padding: 10px 14px;
  background: #fafafa;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

/* ===== Hot Grid ===== */
.hot-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 20px;
}

.hot-card {
  cursor: pointer;
  border-radius: 12px;
}

.hot-img-wrapper {
  position: relative;
  aspect-ratio: 4 / 3;
  overflow: hidden;
  border-radius: 12px 12px 0 0;
}

.hot-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s;

  .hot-card:hover & {
    transform: scale(1.05);
  }
}

.hot-overlay {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 8px 12px;
  background: linear-gradient(transparent, rgba(0, 0, 0, 0.6));
}

.hot-price {
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.hot-info {
  padding: 12px;
}

.hot-name {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hot-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #909399;
}

/* ===== Recommend ===== */
.recommend-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 20px;
}

.recommend-card {
  display: flex;
  cursor: pointer;
  border-radius: 12px;
  overflow: hidden;
}

.rec-img {
  width: 200px;
  height: 160px;
  object-fit: cover;
  flex-shrink: 0;
}

.rec-content {
  flex: 1;
  padding: 16px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
}

.rec-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 8px;
}

.rec-name {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rec-desc {
  font-size: 13px;
  color: #909399;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
}

.rec-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.rec-bottom-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rec-score {
  font-size: 12px;
  color: #409eff;
  font-weight: 600;
  white-space: nowrap;
}

/* ===== Preference ===== */
.preference-tip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: #ecf5ff;
  border-radius: 8px;
  color: #409eff;
  cursor: pointer;
  margin-bottom: 16px;
  transition: background 0.2s;

  &:hover {
    background: #d9ecff;
  }
}

.preference-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

/* ===== Responsive ===== */
@media (max-width: 992px) {
  .hot-grid {
    grid-template-columns: repeat(3, 1fr);
  }

  .recommend-list {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .hot-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .hero-title {
    font-size: 32px;
  }

  .hero-subtitle {
    font-size: 16px;
  }
}
</style>

