<script setup>
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useChatStore } from '../stores/chat'
import { useUiStore } from '../stores/ui'
import MessageBubble from '../components/MessageBubble.vue'
import EmptyState from '../components/EmptyState.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const chat = useChatStore()
const ui = useUiStore()
const { conversations, activeId, messages, streaming } = storeToRefs(chat)

const keyword = ref('')
const draft = ref('')
const scrollEl = ref(null)
const editingId = ref(null)
const editingText = ref('')
const deleting = ref(null)

const filtered = computed(() =>
  conversations.value.filter((c) => !keyword.value || c.title?.toLowerCase().includes(keyword.value.toLowerCase()))
)

const emptyState = computed(() => !messages.value.length)
const SUGGESTIONS = ['帮我巡检一下数据库健康状态', '最近有没有慢查询？', '如何排查数据库死锁？']

onMounted(async () => {
  try {
    await chat.fetchConversations()
  } catch (e) {
    ui.error(e.message)
  }
  scrollToBottom(true)
})

watch([() => messages.value.length, () => messages.value[messages.value.length - 1]?.content], scrollToBottom)

function scrollToBottom(force = false) {
  nextTick(() => {
    const el = scrollEl.value
    if (!el) return
    const nearBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 160
    if (force || nearBottom) el.scrollTop = el.scrollHeight
  })
}

async function send(text = draft.value) {
  const query = (text || '').trim()
  if (!query || streaming.value) return
  draft.value = ''
  try {
    await chat.send(query)
  } catch (e) {
    ui.error(e.message)
  }
}

async function newConv() {
  try {
    await chat.newConversation()
  } catch (e) {
    ui.error(e.message)
  }
}

async function select(id) {
  try {
    await chat.selectConversation(id)
    scrollToBottom(true)
  } catch (e) {
    ui.error(e.message)
  }
}

function startRename(item) {
  editingId.value = item.conversationId
  editingText.value = item.title || ''
}

async function commitRename(id) {
  const title = editingText.value.trim()
  editingId.value = null
  if (title) {
    try {
      await chat.renameConversation(id, title)
    } catch (e) {
      ui.error(e.message)
    }
  }
}

async function doDelete() {
  if (!deleting.value) return
  try {
    await chat.removeConversation(deleting.value)
    ui.success('会话已删除')
  } catch (e) {
    ui.error(e.message)
  } finally {
    deleting.value = null
  }
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}
</script>

<template>
  <div class="chat">
    <aside class="conv-panel card">
      <div class="conv-head">
        <span class="conv-title">会话</span>
        <button class="btn btn-primary btn-sm" @click="newConv">＋ 新对话</button>
      </div>
      <input v-model="keyword" class="input" placeholder="搜索会话…" />
      <div class="conv-list">
        <div
          v-for="c in filtered"
          :key="c.conversationId"
          class="conv-item"
          :class="{ active: c.conversationId === activeId }"
          @click="select(c.conversationId)"
        >
          <template v-if="editingId === c.conversationId">
            <input
              v-model="editingText"
              class="input rename-input"
              autofocus
              @click.stop
              @keydown.enter="commitRename(c.conversationId)"
              @keydown.esc="editingId = null"
              @blur="commitRename(c.conversationId)"
            />
          </template>
          <template v-else>
            <div class="conv-line1">
              <span class="truncate conv-name">{{ c.title || '新会话' }}</span>
              <span class="conv-actions" @click.stop>
                <button class="icon-btn" title="重命名" @click="startRename(c)">✎</button>
                <button class="icon-btn danger" title="删除" @click="deleting = c.conversationId">🗑</button>
              </span>
            </div>
            <div class="conv-line2">
              <span class="muted small">{{ c.messageCount }} 条</span>
            </div>
          </template>
        </div>
        <div v-if="!filtered.length" class="conv-empty muted">暂无会话</div>
      </div>
    </aside>

    <section class="msg-panel">
      <div ref="scrollEl" class="messages">
        <div v-if="emptyState" class="welcome">
          <h2>你好，我是 DB Ops Agent 👋</h2>
          <p class="muted">我可以执行数据库巡检、知识问答与故障排查，试试下面的快捷指令</p>
          <div class="sugs">
            <button v-for="s in SUGGESTIONS" :key="s" class="chip" @click="send(s)">{{ s }}</button>
          </div>
        </div>
        <template v-else>
          <MessageBubble v-for="m in messages" :key="m.id" :message="m" />
          <div ref="endEl" />
        </template>
      </div>

      <div class="composer">
        <textarea
          v-model="draft"
          class="textarea composer-input"
          :placeholder="streaming ? 'AI 正在回答…' : '输入你的问题，Enter 发送，Shift+Enter 换行'"
          :disabled="streaming"
          @keydown="onKeydown"
        />
        <div class="composer-actions">
          <span class="hint muted small">{{ streaming ? '生成中，可随时停止' : 'Enter 发送 · Shift+Enter 换行' }}</span>
          <button v-if="streaming" class="btn btn-danger" @click="chat.stop()">停止</button>
          <button v-else class="btn btn-primary" :disabled="!draft.trim()" @click="send()">发送</button>
        </div>
      </div>
    </section>

    <ConfirmDialog
      v-if="deleting"
      title="删除会话"
      message="删除后该会话的历史消息将不可恢复，确定删除？"
      confirm-text="删除"
      danger
      @confirm="doDelete"
      @cancel="deleting = null"
    />
  </div>
</template>

<style scoped>
.chat {
  display: flex; gap: 14px;
  height: calc(100vh - 56px - 40px); min-height: 520px;
}
.conv-panel { width: 272px; flex-shrink: 0; display: flex; flex-direction: column; padding: 14px; gap: 10px; }
.conv-head { display: flex; align-items: center; justify-content: space-between; }
.conv-title { font-weight: 600; font-size: 14px; }
.conv-list { flex: 1; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
.conv-item { padding: 9px 10px; border-radius: var(--radius-sm); cursor: pointer; border: 1px solid transparent; }
.conv-item:hover { background: #f8fafc; }
.conv-item.active { background: var(--primary-light); border-color: #c7d2fe; }
.conv-line1 { display: flex; align-items: center; gap: 6px; }
.conv-name { font-size: 13px; flex: 1; }
.conv-actions { display: none; gap: 2px; }
.conv-item:hover .conv-actions { display: flex; }
.icon-btn { color: var(--text-muted); font-size: 12px; padding: 2px 4px; border-radius: 4px; }
.icon-btn:hover { color: var(--text); background: #eef2ff; }
.icon-btn.danger:hover { color: var(--danger); background: #fef2f2; }
.rename-input { padding: 4px 8px; font-size: 13px; }
.conv-empty { padding: 16px; text-align: center; font-size: 13px; }

.msg-panel { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.messages { flex: 1; overflow-y: auto; padding: 8px 4px 4px; }
.welcome { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; text-align: center; }
.welcome h2 { font-size: 20px; }
.sugs { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; margin-top: 10px; }
.chip {
  border: 1px solid var(--border); background: var(--surface); padding: 8px 14px;
  border-radius: 999px; font-size: 13px; color: var(--text-secondary); transition: all .15s ease;
}
.chip:hover { border-color: var(--primary); color: var(--primary); box-shadow: var(--shadow-sm); }
.composer { border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); padding: 10px; }
.composer-input { border: none; resize: none; min-height: 48px; max-height: 160px; padding: 6px 4px; background: transparent; }
.composer-input:focus { border-color: transparent; }
.composer-actions { display: flex; align-items: center; justify-content: space-between; padding-top: 6px; }
</style>
