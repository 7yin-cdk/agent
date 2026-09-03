<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import * as kbApi from '../api/kb'
import { useUiStore } from '../stores/ui'
import EmptyState from '../components/EmptyState.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

const ui = useUiStore()

const STATUS = {
  UPLOADED: { text: '排队中', cls: 'badge-info' },
  PARSED: { text: '解析中', cls: 'badge-warning' },
  EMBEDDED: { text: '已入库', cls: 'badge-success' },
  FAILED: { text: '失败', cls: 'badge-danger' }
}
const fmtTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '')
const fmtSize = (n) => {
  if (n == null) return '—'
  if (n < 1024) return `${n}B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`
  return `${(n / 1024 / 1024).toFixed(2)}MB`
}

const tab = ref('docs')
const pageData = ref({ total: 0, page: 1, size: 10, items: [] })
const keyword = ref('')
const uploading = ref(false)
const fileInput = ref(null)
const deleting = ref(null)
const chunks = ref(null) /* 切片抽屉数据 {fileId,fileName, list,page,total} */

/* 检索调试 */
const rq = ref('')
const rTopK = ref(8)
const rRerank = ref(true)
const retrieving = ref(false)
const results = ref(null)

let pollTimer = null
onMounted(loadDocs)
onBeforeUnmount(() => clearTimeout(pollTimer))

async function loadDocs() {
  try {
    const params = { page: pageData.value.page, size: pageData.value.size }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    pageData.value = await kbApi.pageDocuments(params)
    maybePoll()
  } catch (e) {
    ui.error(e.message)
  }
}
const totalPages = computed(() => Math.ceil(pageData.value.total / pageData.value.size))

/* 上传后存在排队中的文档则短暂轮询直至状态稳定 */
function maybePoll() {
  clearTimeout(pollTimer)
  const pending = pageData.value.items.some((f) => f.status === 'UPLOADED' || f.status === 'PARSED')
  if (pending) pollTimer = setTimeout(loadDocs, 2000)
}

function triggerUpload() {
  fileInput.value?.click()
}

async function onFile(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  uploading.value = true
  try {
    await kbApi.uploadDocument(file)
    ui.success('上传成功，正在后台解析入库…')
    pageData.value.page = 1
    await loadDocs()
  } catch (err) {
    ui.error(err.message)
  } finally {
    uploading.value = false
  }
}

async function confirmDelete() {
  if (!deleting.value) return
  try {
    await kbApi.deleteDocument(deleting.value)
    ui.success('文档已删除')
    if (chunks.value?.fileId === deleting.value) chunks.value = null
    await loadDocs()
  } catch (e) {
    ui.error(e.message)
  } finally {
    deleting.value = null
  }
}

function pageNav(delta) {
  const next = pageData.value.page + delta
  if (next < 1 || next > totalPages.value) return
  pageData.value.page = next
  loadDocs()
}

async function openChunks(f) {
  try {
    const res = await kbApi.pageChunks(f.id, { page: 1, size: 50 })
    chunks.value = { fileId: f.id, fileName: f.fileName, list: res.items, total: res.total }
  } catch (e) {
    ui.error(e.message)
  }
}

async function doRetrieve() {
  const query = rq.value.trim()
  if (!query) return ui.error('请输入检索问题')
  retrieving.value = true
  results.value = null
  try {
    results.value = await kbApi.retrieve({ query, topK: rTopK.value, rerank: rRerank.value })
  } catch (e) {
    ui.error(e.message)
  } finally {
    retrieving.value = false
  }
}

const snippet = (s, n = 180) => (s ? (s.length > n ? `${s.slice(0, n)}…` : s) : '')
</script>

<template>
  <div class="kb">
    <div class="tabs card">
      <button class="tab" :class="{ on: tab === 'docs' }" @click="tab = 'docs'">知识文档</button>
      <button class="tab" :class="{ on: tab === 'retrieve' }" @click="tab = 'retrieve'">检索调试</button>
    </div>

    <!-- 文档管理 -->
    <template v-if="tab === 'docs'">
      <div class="card toolbar">
        <input v-model="keyword" class="input" placeholder="按文件名搜索…" @keydown.enter="pageData.page = 1; loadDocs()" />
        <button class="btn btn-ghost" @click="pageData.page = 1; loadDocs()">查询</button>
        <button class="btn btn-primary" :disabled="uploading" @click="triggerUpload">
          {{ uploading ? '上传中…' : '＋ 上传文档' }}
        </button>
        <input ref="fileInput" type="file" hidden @change="onFile" />
      </div>

      <div class="card table-card">
        <table class="table">
          <thead>
            <tr>
              <th>文件名</th><th>状态</th><th>切片数</th><th>大小</th><th>上传时间</th><th style="width:150px">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in pageData.items" :key="f.id">
              <td class="truncate" style="max-width: 300px">{{ f.fileName }}</td>
              <td><span class="badge" :class="STATUS[f.status]?.cls">{{ STATUS[f.status]?.text || f.status }}</span></td>
              <td class="mono small">{{ f.chunkCount ?? 0 }}</td>
              <td class="small">{{ fmtSize(f.fileSize) }}</td>
              <td class="small muted">{{ fmtTime(f.createdAt) }}</td>
              <td>
                <button class="btn btn-ghost btn-sm" @click="openChunks(f)">切片</button>
                <button class="btn btn-danger btn-sm" @click="deleting = f.id">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <EmptyState v-if="!pageData.items.length" text="暂无文档" sub="上传 PDF / Word / Markdown 等文档，异步切片向量化入库" />
        <div v-if="pageData.total > pageData.size" class="pager">
          <button class="btn btn-ghost btn-sm" :disabled="pageData.page <= 1" @click="pageNav(-1)">上一页</button>
          <span class="small secondary">{{ pageData.page }} / {{ totalPages }}（共 {{ pageData.total }}）</span>
          <button class="btn btn-ghost btn-sm" :disabled="pageData.page >= totalPages" @click="pageNav(1)">下一页</button>
        </div>
      </div>
    </template>

    <!-- 检索调试 -->
    <template v-else>
      <div class="card retrieve-box">
        <textarea v-model="rq" class="textarea" placeholder="输入一个问题，查看命中片段与相似度（模拟 RAG 检索管线）" />
        <div class="retrieve-opts">
          <label class="opt">TopK <input v-model.number="rTopK" type="number" min="1" max="50" class="input input-num" /></label>
          <label class="opt check">
            <input v-model="rRerank" type="checkbox" /> rerank 重排
          </label>
          <button class="btn btn-primary" :disabled="retrieving" @click="doRetrieve">
            {{ retrieving ? '检索中…' : '开始检索' }}
          </button>
        </div>
      </div>

      <div v-if="results" class="results">
        <div class="result-count small muted">命中 {{ results.length }} 条片段</div>
        <div v-for="(r, i) in results" :key="r.chunkId + i" class="card hit">
          <div class="hit-head">
            <span class="rank mono">#{{ i + 1 }}</span>
            <span class="file-name mono truncate">{{ r.fileName }}</span>
            <span class="badge badge-info">切片 {{ r.chunkIndex }}</span>
            <span v-if="r.score != null" class="badge badge-success">相似度 {{ Number(r.score).toFixed(4) }}</span>
          </div>
          <div class="hit-text">{{ snippet(r.chunkText) }}</div>
        </div>
        <EmptyState v-if="!results.length" text="未命中任何片段" />
      </div>
      <div v-else class="card">
        <EmptyState text="输入问题开始检索调试" sub="该功能直接调用与生产 RAG 一致的检索管线，便于验证召回效果" />
      </div>
    </template>

    <!-- 切片抽屉 -->
    <div v-if="chunks" class="mask" @click.self="chunks = null">
      <aside class="drawer card">
        <div class="drawer-head">
          <div>
            <div class="small muted">切片查看</div>
            <h3 class="truncate">{{ chunks.fileName }}</h3>
          </div>
          <button class="btn btn-ghost btn-sm" @click="chunks = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div class="small muted mb-2">共 {{ chunks.total }} 个切片（文本分块，400 字/块 + overlap）</div>
          <div v-for="(c, i) in chunks.list" :key="c.chunkId" class="chunk">
            <div class="chunk-head">
              <span class="mono small">[{{ c.chunkIndex }}]</span>
              <span class="muted small">{{ c.chunkText?.length }} 字</span>
            </div>
            <div class="chunk-text">{{ c.chunkText }}</div>
          </div>
          <EmptyState v-if="!chunks.list.length" text="该文档暂无切片" />
        </div>
      </aside>
    </div>

    <ConfirmDialog
      v-if="deleting"
      title="删除文档"
      message="将级联删除其全部切片与向量索引，且不可恢复。确定删除？"
      confirm-text="删除"
      danger
      @confirm="confirmDelete"
      @cancel="deleting = null"
    />
  </div>
</template>

<style scoped>
.kb { display: flex; flex-direction: column; gap: 14px; max-width: 1100px; margin: 0 auto; }
.tabs { display: inline-flex; padding: 4px; gap: 4px; width: fit-content; }
.tab { padding: 7px 18px; border-radius: var(--radius-sm); font-size: 13.5px; color: var(--text-secondary); }
.tab.on { background: var(--primary-light); color: var(--primary); font-weight: 600; }
.toolbar { display: flex; gap: 10px; padding: 12px; align-items: center; }
.toolbar .input { max-width: 320px; }
.table-card { padding: 8px 12px; }
.pager { display: flex; justify-content: center; align-items: center; gap: 14px; padding: 10px 0; }
.retrieve-box { display: flex; flex-direction: column; gap: 10px; padding: 14px; }
.retrieve-opts { display: flex; align-items: center; gap: 16px; }
.opt { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-secondary); }
.opt.check { gap: 4px; }
.input-num { width: 64px; padding: 4px 8px; }
.results { display: flex; flex-direction: column; gap: 10px; }
.hit { padding: 12px 14px; }
.hit-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; flex-wrap: wrap; }
.rank { color: var(--primary); font-weight: 600; }
.file-name { max-width: 300px; font-weight: 500; }
.hit-text { font-size: 13px; line-height: 1.6; color: var(--text-secondary); word-break: break-word; }
.result-count { padding-left: 4px; }
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.35); z-index: 1500; display: flex; justify-content: flex-end; }
.drawer { width: 560px; max-width: 94vw; height: 100%; border-radius: 0; display: flex; flex-direction: column; animation: slide .2s ease; }
@keyframes slide { from { transform: translateX(100%); } to { transform: none; } }
.drawer-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; padding: 16px; border-bottom: 1px solid var(--border); }
.drawer-head h3 { margin-top: 4px; }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
.chunk { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; background: #fafbfc; }
.chunk-head { display: flex; justify-content: space-between; margin-bottom: 4px; }
.chunk-text { font-size: 12.5px; line-height: 1.6; color: var(--text-secondary); max-height: 200px; overflow: auto; white-space: pre-wrap; }
</style>
