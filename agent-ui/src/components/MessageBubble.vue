<script setup>
import MarkdownRenderer from './MarkdownRenderer.vue'
import ToolCallCard from './ToolCallCard.vue'

const props = defineProps({ message: { type: Object, required: true } })

const INTENT = {
  KNOWLEDGE_BASE: { text: '知识问答', cls: 'badge-info' },
  COMPLEX_TASK: { text: '复杂任务', cls: 'badge-warning' },
  SIMPLE_CHAT: { text: '日常对话', cls: 'badge-neutral' }
}
const intentBadge = () => (props.message.intentType ? INTENT[props.message.intentType] : null)

const copy = async () => {
  await navigator.clipboard.writeText(props.message.content || '')
}
</script>

<template>
  <div class="msg" :class="message.role">
    <div class="avatar" :class="message.role">
      {{ message.role === 'user' ? '我' : 'A' }}
    </div>
    <div class="body">
      <div v-if="message.role === 'user'" class="bubble user-bubble">{{ message.content }}</div>
      <template v-else>
        <div class="bubble ai-bubble" :class="{ error: message.error }">
          <template v-if="message.error && !message.content">
            <span class="err-text">{{ message.error }}</span>
          </template>
          <template v-else>
            <MarkdownRenderer v-if="message.content" :content="message.content" />
            <span v-if="message.streaming" class="typing">
              <span v-if="message.content" class="cursor" />
              <span v-else>思考中…</span>
            </span>
          </template>
        </div>
        <div v-if="!message.error" class="meta">
          <span v-if="intentBadge()" class="badge" :class="intentBadge().cls">{{ intentBadge().text }}</span>
          <button v-if="message.content" class="meta-btn" @click="copy">复制</button>
        </div>
        <ToolCallCard
          v-if="message.trace"
          :tool-calls="message.trace.toolCalls"
          :llm-calls="message.trace.llmCalls"
        />
        <div v-if="message.error && message.content" class="error-hint">
          ⚠ 本次回复出错：{{ message.error }}
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.msg { display: flex; gap: 12px; margin-bottom: 22px; }
.msg.user { flex-direction: row-reverse; }
.avatar {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 600;
}
.avatar.user { background: var(--primary); color: #fff; }
.avatar.assistant { background: #334155; color: #e2e8f0; }
.body { max-width: 78%; min-width: 0; }
.msg.user .body { display: flex; flex-direction: column; align-items: flex-end; }
.bubble { padding: 10px 14px; border-radius: 12px; }
.user-bubble { background: var(--primary); color: #fff; white-space: pre-wrap; word-break: break-word; }
.ai-bubble { background: var(--surface); border: 1px solid var(--border); box-shadow: var(--shadow-sm); }
.ai-bubble.error { border-color: #fecaca; }
.err-text { color: var(--danger); }
.typing { color: var(--text-muted); font-size: 13px; }
.cursor {
  display: inline-block; width: 2px; height: 1em; background: var(--primary);
  margin-left: 2px; vertical-align: text-bottom; animation: blink .8s infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }
.meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; padding: 0 2px; }
.meta-btn { color: var(--text-muted); font-size: 12px; }
.meta-btn:hover { color: var(--primary); }
.error-hint { margin-top: 6px; font-size: 12px; color: var(--danger); }
</style>
