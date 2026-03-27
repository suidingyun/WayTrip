<template>
  <div class="rec-ai-panel">
    <div class="rec-ai-head">
      <span class="rec-ai-title">为您推荐</span>
      <span class="rec-ai-badge">AI 解读 · 可继续追问</span>
    </div>

    <div v-if="hasSystemHint" class="rec-system">
      <div v-if="insight.score != null" class="rec-score">
        系统匹配度：<strong>{{ formatScore(insight.score) }}</strong>
      </div>
      <p v-if="insight.reason" class="rec-reason">{{ insight.reason }}</p>
    </div>
    <p v-else class="rec-system muted">
      若从首页「为你推荐」进入，会显示系统给出的匹配说明；您也可以直接问小途为何值得去这里、怎么玩更合适。
    </p>

    <div v-if="!userStore.isLoggedIn" class="rec-login">
      <el-button type="primary" size="small" round @click="goLogin">登录后生成 AI 解读</el-button>
    </div>

    <template v-else>
      <div class="rec-messages" ref="msgBox">
        <div
          v-for="(m, i) in messages"
          :key="i"
          :class="['rec-bubble', m.role === 'user' ? 'user' : 'assistant']"
        >
          {{ m.displayText ?? m.text }}
        </div>
        <div v-if="loading" class="rec-bubble assistant thinking">小途正在想…</div>
      </div>

      <div v-if="messages.length === 0 && !loading" class="rec-boot-row">
        <el-button type="primary" size="small" :loading="loading" @click="runBootstrap">
          生成 AI 解读
        </el-button>
      </div>

      <div class="rec-composer">
        <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          maxlength="2000"
          show-word-limit
          placeholder="继续追问，例如：带老人小孩怎么玩？"
          @keydown.enter.exact.prevent="send"
        />
        <el-button
          type="primary"
          class="rec-send"
          size="small"
          :loading="loading"
          :disabled="!input.trim()"
          @click="send"
        >
          发送
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { assistantChat } from '@/api/assistant'

const props = defineProps({
  spot: {
    type: Object,
    required: true
  },
  recommendInsight: {
    type: Object,
    default: () => ({ reason: '', score: null })
  }
})

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

const insight = computed(() => props.recommendInsight || {})
const hasSystemHint = computed(
  () => insight.value.reason || insight.value.score != null
)

function formatScore(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '-'
}

const input = ref('')
const loading = ref(false)
const messages = ref([])
const msgBox = ref(null)

function newChatId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'rec-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

const chatId = ref(newChatId())

function buildBootstrapUserText() {
  const name = props.spot?.name || '该景点'
  const scoreText =
    insight.value.score != null ? formatScore(insight.value.score) : '未提供'
  const reasonText =
    insight.value.reason ||
    '（用户可能从搜索或列表直接进入，暂无系统生成的推荐摘要，请结合景点本身与时节作答）'
  return (
    `我是从推荐/浏览场景进入「${name}」详情页的。请根据下面系统给出的匹配度与说明，用 2～4 段话向用户解释：` +
    `这次推荐可能考虑了哪些因素、这个景点适合谁、游玩时有什么实用建议。语气亲切简洁，勿重复堆砌系统原文。\n\n` +
    `【系统匹配度】${scoreText}\n【系统推荐摘要】${reasonText}`
  )
}

const scrollBottom = async () => {
  await nextTick()
  const el = msgBox.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(messages, () => scrollBottom(), { deep: true })

async function dispatch(text, displayText) {
  if (!text?.trim() || loading.value) return
  const trimmed = text.trim()
  messages.value.push({
    role: 'user',
    text: trimmed,
    ...(displayText ? { displayText } : {})
  })
  input.value = ''
  loading.value = true
  try {
    const payload = {
      message: trimmed,
      chatId: chatId.value,
      province: props.spot?.regionName || undefined,
      spotId: props.spot?.id
    }
    const res = await assistantChat(payload)
    const reply = res.data?.reply ?? res.data
    const out = typeof reply === 'string' ? reply : JSON.stringify(reply)
    messages.value.push({ role: 'assistant', text: out || '（空回复）' })
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      text: '暂时无法连接小途：' + (e.message || '请稍后重试')
    })
  } finally {
    loading.value = false
  }
}

function runBootstrap() {
  dispatch(buildBootstrapUserText(), '请根据本次推荐上下文，解读推荐理由与游玩建议')
}

function send() {
  const text = input.value.trim()
  if (!text) return
  dispatch(text)
}

function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function resetConversation() {
  messages.value = []
  chatId.value = newChatId()
}

watch(
  () => props.spot?.id,
  (id, oldId) => {
    if (id == null) return
    if (id !== oldId) {
      resetConversation()
    }
    if (userStore.isLoggedIn && messages.value.length === 0) {
      runBootstrap()
    }
  },
  { immediate: true }
)

watch(
  () => userStore.isLoggedIn,
  (logged) => {
    if (!logged) {
      resetConversation()
      return
    }
    if (props.spot?.id && messages.value.length === 0) {
      runBootstrap()
    }
  }
)
</script>

<style scoped>
.rec-ai-panel {
  margin-bottom: 16px;
  padding: 14px;
  background: linear-gradient(180deg, #f0f7ff 0%, #f4f4f5 100%);
  border-radius: 10px;
  border-left: 4px solid #409eff;
}
.rec-ai-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  margin-bottom: 10px;
}
.rec-ai-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.rec-ai-badge {
  font-size: 12px;
  color: #409eff;
  font-weight: 500;
}
.rec-system {
  font-size: 13px;
  color: #606266;
  line-height: 1.55;
  margin-bottom: 12px;
}
.rec-system.muted {
  color: #909399;
  margin-bottom: 12px;
}
.rec-score {
  margin-bottom: 6px;
}
.rec-reason {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}
.rec-login {
  text-align: center;
  padding: 8px 0;
}
.rec-messages {
  max-height: 240px;
  overflow-y: auto;
  margin-bottom: 10px;
  padding: 8px;
  background: #fff;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
}
.rec-bubble {
  max-width: 100%;
  padding: 8px 10px;
  border-radius: 10px;
  margin-bottom: 8px;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-word;
}
.rec-bubble.user {
  margin-left: 12px;
  background: var(--el-color-primary-light-9);
  color: #303133;
}
.rec-bubble.assistant {
  margin-right: 12px;
  background: #f5f7fa;
}
.rec-bubble.thinking {
  opacity: 0.8;
  font-style: italic;
}
.rec-boot-row {
  margin-bottom: 10px;
}
.rec-composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rec-send {
  align-self: stretch;
}
</style>
