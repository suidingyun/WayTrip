<template>
  <div class="page-container assistant-page">
    <div class="assistant-hero card">
      <h1 class="page-title"><span class="emoji" aria-hidden="true">🧭</span> 小途助手</h1>
      <p class="page-desc">
        行程规划、目的地问答、美食交通与避坑建议都可以问我。与首页悬浮助手使用不同对话记录，互不干扰。
      </p>
    </div>
    <div class="assistant-card card">
      <TravelAssistantChat variant="page" />
    </div>
  </div>
</template>

<script setup>
import TravelAssistantChat from '@/components/TravelAssistantChat.vue'
</script>

<style lang="scss" scoped>
.assistant-page {
  max-width: 800px;
  margin: 0 auto;
  padding-top: 16px;
  padding-bottom: 48px;
}
.assistant-hero {
  padding: 24px 28px;
  margin-bottom: 20px;
  border-radius: 12px;
}
.page-title {
  margin: 0 0 12px;
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  display: flex;
  align-items: center;
  gap: 10px;
}
.emoji {
  font-size: 28px;
}
.page-desc {
  margin: 0;
  font-size: 14px;
  color: #606266;
  line-height: 1.65;
}
.assistant-card {
  padding: 24px;
  border-radius: 12px;
  min-height: 400px;
}
</style>
