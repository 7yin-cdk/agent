<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import * as memApi from '../api/memory'
import { useUiStore } from '../stores/ui'
import EmptyState from '../components/EmptyState.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const ui = useUiStore()

const CATEGORIES = {
  USER_PROFILE: { text: '用户画像', cls: 'badge-info' },
  PREFERENCE: { text: '偏好', cls: 'badge-info' },
  CONSTRAINT: { text: '约束', cls: 'badge-warning' },
  ENTITY: { text: '实体', cls: 'badge-success' },
  EXPERIENCE: { text: '经验', cls: 'badge-neutral' }
}

const pageData = ref({ total: 0, page: 1, size: 20, items: [] })
const category = ref('')
const searchQuery = ref('')
const searchMode = ref(false)
const searchResults = ref([])
const loading = ref(false)

const dialog = reactive({ open: false, mode: 'add', id: null })
const form = reactive({
  category: 'EXPERIENCE',
  content: '',
  keywords: '',
  entity: '',
  entityType: '',
  importance: 5,
  confidence: 0.8,
  expiredAt: ''
})

const clearTarget = ref(null) /* 'ALL' 或分类名 */

const items = computed(() => (searchMode.value ? searchResults.value : pageData.value.items))

onMounted(load)

async function load() {
  loading.value = true
  try {
    const params = { page: pageData.value.page, size: pageData.value.size }
    if (category.value) params.category = category.value
    const res = await memApi.pageMemories(params)
    pageData.value = res
  } catch (e) {
    ui.error(e.message)
  } finally {
    loading.value = false
  }
}

async function doSearch() {
  const q = searchQuery.value.trim()
  if (!q) {
    searchMode.value = false
    return load()
  }
  loading.value = true
  try {
    searchResults.value = await memApi.searchMemories({ query: q, topK: 20 })
    searchMode.value = true
  } catch (e) {
    ui.error(e.message)
  } finally {
    loading.value = false
  }
}

function pickCategory(c) {
  category.value = category.value === c ? '' : c
  pageData.value.page = 1
  if (searchMode.value) {
    searchMode.value = false
    searchQuery.value = ''
  }
  load()
}

function openAdd() {
  Object.assign(form, { category: 'EXPERIENCE', content: '', keywords: '', entity: '', entityType: '', importance: 5, confidence: 0.8, expiredAt: '' })
  dialog.mode = 'add'
  dialog.id = null
  dialog.open = true
}

function openEdit(m) {
  Object.assign(form, {
    category: m.category,
    content: m.content,
    keywords: m.keywords?.join(',') || '',
    entity: m.entity || '',
    entityType: m.entityType || '',
    importance: m.importance ?? 5,
    confidence: m.confidence ?? 0.8,
    expiredAt: toLocalInput(m.expiredAt)
  })
  dialog.mode = 'edit'
  dialog.id = m.id
  dialog.open = true
}

const toLocalInput = (v) => (v ? String(v).slice(0, 16) : '')

async function submitForm() {
  if (!form.content.trim()) return ui.error('记忆内容不能为空')
  const payload = {
    category: form.category,
    content: form.content.trim(),
    importance: Number(form.importance),
    confidence: Number(form.confidence)
  }
  if (form.keywords.trim()) payload.keywords = form.keywords.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
  if (form.entity.trim()) payload.entity = form.entity.trim()
  if (form.entityType.trim()) payload.entityType = form.entityType.trim()
  if (form.expiredAt) payload.expiredAt = form.expiredAt
  try {
    if (dialog.mode === 'add') {
      await memApi.addMemory(payload)
      ui.success('记忆已添加')
    } else {
      await memApi.updateMemory(dialog.id, payload)
      ui.success('记忆已更新')
    }
    dialog.open = false
    load()
    if (searchMode.value) doSearch()
  } catch (e) {
    ui.error(e.message)
  }
}

async function removeItem(m) {
  try {
    await memApi.deleteMemory(m.id)
    ui.success('已删除')
    load()
    if (searchMode.value) doSearch()
  } catch (e) {
    ui.error(e.message)
  }
}

async function confirmClear() {
  if (!clearTarget.value) return
  try {
    await memApi.clearMemories(clearTarget.value === 'ALL' ? undefined : clearTarget.value)
    ui.success('已清空')
    clearTarget.value = null
    load()
  } catch (e) {
    ui.error(e.message)
    clearTarget.value = null
  }
}

function pageNav(delta) {
  const next = pageData.value.page + delta
  if (next < 1 || next > Math.ceil(pageData.value.total / pageData.value.size)) return
  pageData.value.page = next
  load()
}

const totalPages = computed(() => Math.ceil(pageData.value.total / pageData.value.size))
</script>

<template>
  <div class="memory">
    <div class="card toolbar">
      <div class="search-box">
        <input
          v-model="searchQuery"
          class="input"
          placeholder="语义检索记忆内容…"
          @keydown.enter="doSearch"
        />
        <button class="btn btn-primary" @click="doSearch">检索</button>
        <button v-if="searchMode" class="btn btn-ghost" @click="searchMode = false; searchQuery = ''; load()">返回列表</button>
      </div>
      <div class="actions">
        <button class="btn btn-danger btn-sm" @click="clearTarget = 'ALL'">清空全部</button>
        <button class="btn btn-primary" @click="openAdd">＋ 新增记忆</button>
      </div>
    </div>

    <div class="filters">
      <button class="chip" :class="{ on: !category }" @click="pickCategory('')">全部</button>
      <button
        v-for="(meta, key) in CATEGORIES"
        :key="key"
        class="chip"
        :class="{ on: category === key }"
        @click="pickCategory(key)"
      >
        {{ meta.text }}
      </button>
      <span v-if="clearTarget" style="margin-left:auto"></span>
    </div>

    <div v-if="loading" class="muted small" style="padding:12px">加载中…</div>

    <div v-else-if="items.length" class="grid">
      <div v-for="m in items" :key="m.id" class="card mem-card">
        <div class="mem-head">
          <span class="badge" :class="CATEGORIES[m.category]?.cls">{{ CATEGORIES[m.category]?.text || m.category }}</span>
          <span v-if="m.similarity != null" class="badge badge-success">相似度 {{ m.similarity.toFixed(3) }}</span>
          <span class="actions">
            <button class="icon-btn" title="编辑" @click="openEdit(m)">✎</button>
            <button class="icon-btn danger" title="删除" @click="removeItem(m)">🗑</button>
          </span>
        </div>
        <div class="content">{{ m.content }}</div>
        <div v-if="m.entity || m.keywords?.length" class="tags">
          <span v-if="m.entity" class="tag mono">{{ m.entity }}<template v-if="m.entityType"> · {{ m.entityType }}</template></span>
          <span v-for="k in (m.keywords || [])" :key="k" class="tag">{{ k }}</span>
        </div>
        <div class="mem-foot">
          <span class="muted small">重要度 {{ m.importance ?? '—' }} · 置信 {{ m.confidence ?? '—' }}</span>
          <span class="muted small">{{ m.accessCount != null ? `召回 ${m.accessCount} 次` : '' }}</span>
        </div>
      </div>
    </div>
    <div v-else class="card">
      <EmptyState text="暂无记忆" sub="对话后由 Agent 自动沉淀，也可手动添加" />
    </div>

    <div v-if="!searchMode && pageData.total > pageData.size" class="pager">
      <button class="btn btn-ghost btn-sm" :disabled="pageData.page <= 1" @click="pageNav(-1)">上一页</button>
      <span class="small secondary">{{ pageData.page }} / {{ totalPages }}（共 {{ pageData.total }} 条）</span>
      <button class="btn btn-ghost btn-sm" :disabled="pageData.page >= totalPages" @click="pageNav(1)">下一页</button>
    </div>

    <!-- 新增/编辑弹窗 -->
    <div v-if="dialog.open" class="mask" @click.self="dialog.open = false">
      <div class="card modal">
        <h3>{{ dialog.mode === 'add' ? '新增记忆' : '编辑记忆' }}</h3>
        <div class="form">
          <div class="row">
            <div class="field grow">
              <label class="label">分类 *</label>
              <select v-model="form.category" class="select">
                <option v-for="(meta, key) in CATEGORIES" :key="key" :value="key">{{ meta.text }}</option>
              </select>
            </div>
            <div class="field">
              <label class="label">重要度 (1-10)</label>
              <input v-model.number="form.importance" type="number" min="1" max="10" class="input" />
            </div>
          </div>
          <div class="field">
            <label class="label">内容 *</label>
            <textarea v-model="form.content" class="textarea" placeholder="记忆内容" />
          </div>
          <div class="row">
            <div class="field grow">
              <label class="label">关键词（逗号分隔）</label>
              <input v-model="form.keywords" class="input" placeholder="kw1,kw2" />
            </div>
          </div>
          <div class="row">
            <div class="field grow">
              <label class="label">实体</label>
              <input v-model="form.entity" class="input" placeholder="实体名（可选）" />
            </div>
            <div class="field">
              <label class="label">实体类型</label>
              <input v-model="form.entityType" class="input" placeholder="如 host/db" />
            </div>
          </div>
          <div class="row">
            <div class="field grow">
              <label class="label">置信度 (0-1)</label>
              <input v-model.number="form.confidence" type="number" step="0.1" min="0" max="1" class="input" />
            </div>
            <div class="field grow">
              <label class="label">过期时间（可空）</label>
              <input v-model="form.expiredAt" type="datetime-local" class="input" />
            </div>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="dialog.open = false">取消</button>
          <button class="btn btn-primary" @click="submitForm">保存</button>
        </div>
      </div>
    </div>

    <ConfirmDialog
      v-if="clearTarget"
      :title="clearTarget === 'ALL' ? '清空全部长期记忆' : '清空该分类记忆'"
      message="该操作将逻辑删除对应记忆且不可恢复，确定继续？"
      confirm-text="清空"
      danger
      @confirm="confirmClear"
      @cancel="clearTarget = null"
    />
  </div>
</template>

<style scoped>
.memory { display: flex; flex-direction: column; gap: 14px; max-width: 1080px; margin: 0 auto; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; }
.search-box { display: flex; gap: 8px; flex: 1; max-width: 520px; }
.actions { display: flex; gap: 8px; }
.filters { display: flex; flex-wrap: wrap; gap: 8px; }
.chip {
  padding: 6px 14px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface);
  font-size: 13px; color: var(--text-secondary); transition: all .15s;
}
.chip:hover { border-color: var(--primary); color: var(--primary); }
.chip.on { background: var(--primary); border-color: var(--primary); color: #fff; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.mem-card { padding: 14px; display: flex; flex-direction: column; gap: 10px; }
.mem-head { display: flex; align-items: center; gap: 8px; }
.actions { margin-left: auto; display: flex; gap: 2px; }
.icon-btn { color: var(--text-muted); font-size: 13px; padding: 2px 5px; border-radius: 4px; }
.icon-btn:hover { color: var(--text); background: #eef2ff; }
.icon-btn.danger:hover { color: var(--danger); background: #fef2f2; }
.content { font-size: 13.5px; line-height: 1.6; word-break: break-word; }
.tags { display: flex; flex-wrap: wrap; gap: 6px; }
.tag { font-size: 11px; padding: 2px 8px; background: #f1f5f9; border-radius: 999px; color: var(--text-secondary); }
.tag.mono { font-family: Consolas, monospace; }
.mem-foot { display: flex; justify-content: space-between; align-items: center; border-top: 1px dashed var(--border); padding-top: 8px; }
.pager { display: flex; justify-content: center; align-items: center; gap: 14px; padding: 8px 0; }
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.4); z-index: 2000; display: flex; align-items: center; justify-content: center; }
.modal { width: 520px; max-width: calc(100vw - 40px); padding: 20px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin-bottom: 14px; font-size: 15px; }
.row { display: flex; gap: 12px; }
.field { margin-bottom: 12px; }
.field.grow { flex: 1; min-width: 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }
</style>
