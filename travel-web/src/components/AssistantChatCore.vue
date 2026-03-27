<template>
  <div class="assistant-core" :class="'assistant-core--' + layout">
    <div v-if="!loggedIn" class="login-hint">
      <p>登录后即可使用「小途」旅游助手（推荐解释、攻略问答）。</p>
      <el-button type="primary" @click="$emit('go-login')">去登录</el-button>
    </div>
    <template v-else>
      <div class="messages" ref="msgBoxRef">
        <div v-if="!messages.length" class="welcome">
          {{ welcomeText }}
        </div>
        <div
          v-for="(m, i) in messages"
          :key="i"
          :class="['bubble', m.role === 'user' ? 'user' : 'bot']"
        >
          {{ m.text }}
        </div>
        <div v-if="loading" class="bubble bot thinking">思考中…</div>
      </div>
      <div class="composer">
        <el-input
          :model-value="input"
          type="textarea"
          :rows="textareaRows"
          maxlength="4000"
          show-word-limit
          placeholder="输入你的问题…"
          @update:model-value="$emit('update:input', $event)"
          @keydown.enter.exact.prevent="$emit('send')"
        />
        <el-button
          type="primary"
          class="send-btn"
          :loading="loading"
          :disabled="!input.trim()"
          @click="$emit('send')"
        >
          发送
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  loggedIn: { type: Boolean, default: false },
  messages: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  input: { type: String, default: '' },
  welcomeText: {
    type: String,
    default:
      '你好，我是小途。可以问我目的地怎么玩、美食交通，或让我结合你的推荐列表聊聊～'
  },
  textareaRows: { type: Number, default: 3 },
  /** drawer：侧栏高度；page：专页占满剩余空间 */
  layout: { type: String, default: 'drawer' }
})

defineEmits(['go-login', 'send', 'update:input'])

const msgBoxRef = ref(null)

async function scrollToBottom() {
  await nextTick()
  const el = msgBoxRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(
  () => props.messages,
  () => scrollToBottom(),
  { deep: true }
)

defineExpose({ scrollToBottom })
</script>

<style scoped>
.assistant-core {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.messages {
  min-height: 280px;
  overflow-y: auto;
  padding-bottom: 12px;
}
.assistant-core--drawer .messages {
  height: calc(100vh - 220px);
}
.assistant-core--page .messages {
  height: min(560px, calc(100vh - 360px));
}
.welcome {
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1.6;
  margin-bottom: 12px;
}
.bubble {
  max-width: 92%;
  padding: 10px 12px;
  border-radius: 12px;
  margin-bottom: 10px;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble.user {
  margin-left: auto;
  background: var(--el-color-primary-light-9);
  color: var(--el-text-color-primary);
}
.bubble.bot {
  margin-right: auto;
  background: var(--el-fill-color-light);
}
.bubble.thinking {
  opacity: 0.75;
  font-style: italic;
}
.composer {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 12px;
  margin-top: auto;
}
.send-btn {
  margin-top: 10px;
  width: 100%;
}
.login-hint {
  text-align: center;
  padding: 32px 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
.login-hint .el-button {
  margin-top: 16px;
}
</style>
