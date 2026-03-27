<template>
  <div class="travel-assistant" :class="{ 'travel-assistant--page': variant === 'page' }">
    <template v-if="variant === 'fab'">
      <el-button class="fab" type="primary" round @click="open = true" :title="fabTitle">
        <span class="fab-inner">
          <span class="fab-emoji" aria-hidden="true">🧭</span>
          <span class="fab-label">小途助手</span>
        </span>
      </el-button>

      <el-drawer v-model="open" :title="drawerTitle" direction="rtl" size="min(440px, 92vw)" class="assistant-drawer">
        <AssistantChatCore
          layout="drawer"
          :logged-in="userStore.isLoggedIn"
          :messages="messages"
          :loading="loading"
          v-model:input="input"
          @go-login="goLogin"
          @send="send"
        />
      </el-drawer>
    </template>

    <AssistantChatCore
      v-else
      layout="page"
      :logged-in="userStore.isLoggedIn"
      :messages="messages"
      :loading="loading"
      v-model:input="input"
      @go-login="goLogin"
      @send="send"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { assistantChat } from '@/api/assistant'
import { ElMessage } from 'element-plus'
import AssistantChatCore from '@/components/AssistantChatCore.vue'

const props = defineProps({
  spotId: {
    type: Number,
    default: undefined
  },
  province: {
    type: String,
    default: ''
  },
  /** fab：悬浮钮+侧栏；page：仅内嵌面板（专页使用） */
  variant: {
    type: String,
    default: 'fab',
    validator: (v) => ['fab', 'page'].includes(v)
  }
})

const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

const open = ref(false)
const input = ref('')
const loading = ref(false)
const messages = ref([])

const CHAT_STORAGE_KEY = 'waytrip_assistant_chat_id'
/** 专页使用独立会话 id，避免与悬浮助手上下文混在一起 */
const CHAT_STORAGE_PER_PAGE_KEY = 'waytrip_assistant_chat_page_id'

function newChatId() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'cid-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

function initChatId() {
  if (typeof sessionStorage === 'undefined') return newChatId()
  if (props.variant === 'page') {
    const existing = sessionStorage.getItem(CHAT_STORAGE_PER_PAGE_KEY)
    const id = existing || newChatId()
    if (!existing) sessionStorage.setItem(CHAT_STORAGE_PER_PAGE_KEY, id)
    return id
  }
  const existing = sessionStorage.getItem(CHAT_STORAGE_KEY)
  const id = existing || newChatId()
  if (!existing) sessionStorage.setItem(CHAT_STORAGE_KEY, id)
  return id
}

const chatId = ref(initChatId())

const fabTitle = '旅游助手 · 小途'
const drawerTitle = computed(() =>
  props.spotId ? '🧭 小途 · 景点助手' : '🧭 小途 · 旅游助手'
)

const goLogin = () => {
  if (props.variant === 'fab') open.value = false
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

const send = async () => {
  const text = input.value.trim()
  if (!text || loading.value) return
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    return
  }
  messages.value.push({ role: 'user', text })
  input.value = ''
  loading.value = true
  try {
    const payload = {
      message: text,
      chatId: chatId.value,
      province: props.province || undefined
    }
    if (props.spotId != null && !Number.isNaN(props.spotId)) {
      payload.spotId = props.spotId
    }
    const res = await assistantChat(payload)
    const reply = res.data?.reply ?? res.data
    const out = typeof reply === 'string' ? reply : JSON.stringify(reply)
    messages.value.push({ role: 'assistant', text: out || '（空回复）' })
  } catch (e) {
    messages.value.push({
      role: 'assistant',
      text: '抱歉，暂时无法连接助手：' + (e.message || '请稍后重试')
    })
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.travel-assistant {
  position: relative;
}
.travel-assistant--page {
  width: 100%;
}
.fab {
  position: fixed;
  right: max(16px, env(safe-area-inset-right));
  bottom: max(20px, env(safe-area-inset-bottom));
  z-index: 2000;
  height: 52px;
  padding: 0 22px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  box-shadow: 0 10px 28px rgba(64, 158, 255, 0.42);
  border: none;
}
.fab:hover {
  filter: brightness(1.06);
}
.fab-inner {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}
.fab-emoji {
  font-size: 26px;
  line-height: 1;
}
.fab-label {
  white-space: nowrap;
}
@media (max-width: 480px) {
  .fab {
    height: 48px;
    padding: 0 18px;
    font-size: 14px;
  }
  .fab-emoji {
    font-size: 24px;
  }
}
</style>
