<script setup>
import { ref } from 'vue'

defineProps({
  toolCalls: { type: Array, default: () => [] },
  llmCalls: { type: Array, default: () => [] }
})

const openIds = ref(new Set())
const toggle = (id) => {
  const next = new Set(openIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  openIds.value = next
}

const fmtJson = (v) => {
  if (v == null || v === '') return '—'
  try {
    return typeof v === 'string' ? JSON.stringify(JSON.parse(v), null, 2) : JSON.stringify(v, null, 2)
  } catch (e) {
    return String(v)
  }
}
const typeLabel = (t) => (t === 'KNOWLEDGE_BASE' ? '知识问答' : t === 'COMPLEX_TASK' ? '复杂任务' : '对话')
</script>

<template>
  <div class="trace-box">
    <div class="trace-head">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12h4l2-5 4 10 2-5h6" /></svg>
      执行过程
    </div>

    <div v-if="toolCalls && toolCalls.length" class="group">
      <div class="group-label">工具调用 · {{ toolCalls.length }}</div>
      <div v-for="(tc, i) in toolCalls" :key="'t' + i" class="item">
        <div class="item-row">
          <span class="dot" :class="tc.success ? 'ok' : 'bad'" />
          <span class="tool-name mono">{{ tc.toolName }}</span>
          <span v-if="tc.success === false" class="badge badge-danger">失败</span>
          <span v-if="tc.durationMs != null" class="muted small">{{ tc.durationMs }}ms</span>
          <button class="ghost-sm" @click="toggle('t' + i)">详情</button>
        </div>
        <div v-if="openIds.has('t' + i)" class="detail">
          <div class="kv"><span>入参</span><pre class="mono">{{ fmtJson(tc.toolInput) }}</pre></div>
          <div class="kv"><span>出参</span><pre class="mono">{{ fmtJson(tc.toolOutput) }}</pre></div>
        </div>
      </div>
    </div>

    <div v-if="llmCalls && llmCalls.length" class="group">
      <div class="group-label">模型调用 · {{ llmCalls.length }}</div>
      <div v-for="(lc, i) in llmCalls" :key="'l' + i" class="item">
        <div class="item-row">
          <span class="dot ok" />
          <span class="tool-name mono">{{ lc.modelName }}</span>
          <span class="badge badge-neutral">{{ lc.callType }}</span>
          <span class="muted small">
            {{ lc.inputTokens ?? 0 }}/{{ lc.outputTokens ?? 0 }} tok
            <template v-if="lc.durationMs != null"> · {{ lc.durationMs }}ms</template>
          </span>
          <button class="ghost-sm" @click="toggle('l' + i)">详情</button>
        </div>
        <div v-if="openIds.has('l' + i)" class="detail">
          <div class="kv"><span>输入</span><pre class="mono">{{ lc.inputPrompt }}</pre></div>
          <div v-if="lc.outputResponse" class="kv"><span>输出</span><pre class="mono">{{ lc.outputResponse }}</pre></div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trace-box {
  margin-top: 10px; border: 1px solid var(--border); border-radius: var(--radius-sm);
  background: #f8fafc; font-size: 12.5px; overflow: hidden;
}
.trace-head {
  display: flex; align-items: center; gap: 6px; padding: 6px 10px; color: var(--text-secondary);
  font-weight: 500; border-bottom: 1px solid var(--border);
}
.group { padding: 6px 10px 8px; border-top: 1px dashed var(--border); }
.group:first-of-type { border-top: none; }
.group-label { color: var(--text-muted); font-size: 11px; margin-bottom: 4px; }
.item { padding: 3px 0; }
.item-row { display: flex; align-items: center; gap: 8px; }
.dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.dot.ok { background: var(--success); }
.dot.bad { background: var(--danger); }
.tool-name { font-weight: 500; }
.ghost-sm { color: var(--primary); font-size: 12px; margin-left: auto; }
.ghost-sm:hover { text-decoration: underline; }
.detail { margin-top: 6px; background: #fff; border: 1px solid var(--border); border-radius: 6px; padding: 8px; }
.kv + .kv { margin-top: 6px; }
.kv span { display: block; color: var(--text-muted); font-size: 11px; margin-bottom: 3px; }
.kv pre { white-space: pre-wrap; word-break: break-word; font-size: 12px; max-height: 220px; overflow: auto; }
</style>
