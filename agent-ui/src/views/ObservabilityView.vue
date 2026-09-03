<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import * as echarts from 'echarts'
import { getStats, getTraces, getTraceDetail } from '../api/observability'
import { useUiStore } from '../stores/ui'
import StatCard from '../components/StatCard.vue'
import ToolCallCard from '../components/ToolCallCard.vue'
import EmptyState from '../components/EmptyState.vue'

const ui = useUiStore()
const stats = ref(null)
const traces = ref([])
const loading = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const chartEl = ref(null)
let chart = null

const statusFilter = ref('')
const intentFilter = ref('')
const INTENT = { KNOWLEDGE_BASE: '知识问答', COMPLEX_TASK: '复杂任务', SIMPLE_CHAT: '对话' }

const filtered = computed(() =>
  traces.value.filter(
    (t) =>
      (!statusFilter.value || t.status === statusFilter.value) &&
      (!intentFilter.value || t.intentType === intentFilter.value)
  )
)

const fmtTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '')
const fmtNum = (n) => (n == null ? 0 : Number(n).toLocaleString())
const successRate = computed(() => {
  if (!stats.value || !stats.value.totalTraces) return '—'
  return `${Math.round((stats.value.successCount / stats.value.totalTraces) * 100)}%`
})

onMounted(async () => {
  try {
    const [s, t] = await Promise.all([getStats(), getTraces()])
    stats.value = s
    traces.value = t || []
    await nextTick()
    renderChart()
  } catch (e) {
    ui.error(e.message)
  }
})

function renderChart() {
  const agg = new Map()
  for (const t of traces.value) {
    const day = fmtTime(t.createdAt).slice(0, 10)
    const cur = agg.get(day) || { count: 0, tokens: 0, ok: 0, cost: 0 }
    cur.count += 1
    cur.tokens += t.totalTokens || 0
    cur.cost += t.totalDurationMs || 0
    if (t.status === 'SUCCESS') cur.ok += 1
    agg.set(day, cur)
  }
  const days = [...agg.keys()].sort()
  if (!chartEl.value) return
  chart = chart || echarts.init(chartEl.value)
  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['请求数', '成功数', 'Token 消耗(万)'], top: 0 },
    grid: { left: 40, right: 20, top: 34, bottom: 24 },
    xAxis: { type: 'category', data: days },
    yAxis: { type: 'value', minInterval: 1 },
    series: [
      { name: '请求数', type: 'bar', barMaxWidth: 26, data: days.map((d) => agg.get(d).count), itemStyle: { color: '#6366f1' } },
      { name: '成功数', type: 'bar', barMaxWidth: 26, data: days.map((d) => agg.get(d).ok), itemStyle: { color: '#22c55e' } },
      { name: 'Token 消耗(万)', type: 'line', smooth: true, data: days.map((d) => +(agg.get(d).tokens / 10000).toFixed(2)), itemStyle: { color: '#f59e0b' } }
    ]
  })
}

async function openDetail(t) {
  detailLoading.value = true
  detail.value = null
  try {
    const d = await getTraceDetail(t.traceId)
    detail.value = { ...t, ...d }
  } catch (e) {
    ui.error(e.message)
  } finally {
    detailLoading.value = false
  }
}

function statusBadge(status) {
  return status === 'SUCCESS' ? 'badge-success' : 'badge-danger'
}
const statusText = (s) => (s === 'SUCCESS' ? '成功' : '失败')

watch([statusFilter, intentFilter], () => {
  /* 过滤仅作用于表格，无需重建图表 */
})

function onResize() {
  chart?.resize()
}
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div class="obs">
    <div class="stat-grid">
      <StatCard label="累计请求" :value="fmtNum(stats?.totalTraces)" />
      <StatCard label="成功率" :value="successRate" />
      <StatCard label="Token 消耗" :value="fmtNum(stats?.totalTokens)" />
      <StatCard label="平均耗时" :value="fmtNum(stats?.avgDurationMs)" unit="ms" />
    </div>

    <div class="card chart-card">
      <div ref="chartEl" class="chart" />
    </div>

    <div class="card table-card">
      <div class="toolbar">
        <div class="title">对话链路记录</div>
        <div class="filters">
          <select v-model="statusFilter" class="select">
            <option value="">全部状态</option>
            <option value="SUCCESS">成功</option>
            <option value="ERROR">失败</option>
          </select>
          <select v-model="intentFilter" class="select">
            <option value="">全部意图</option>
            <option v-for="(label, key) in INTENT" :key="key" :value="key">{{ label }}</option>
          </select>
        </div>
      </div>
      <div v-if="filtered.length" class="table-wrap">
        <table class="table">
          <thead>
            <tr>
              <th>时间</th><th>问题</th><th>意图</th><th>状态</th>
              <th>Token</th><th>模型/工具</th><th>耗时</th><th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="t in filtered" :key="t.traceId" @click="openDetail(t)" class="clickable">
              <td class="mono small">{{ fmtTime(t.createdAt) }}</td>
              <td class="truncate" style="max-width: 260px">{{ t.userQuery }}</td>
              <td>{{ INTENT[t.intentType] || t.intentType || '—' }}</td>
              <td><span class="badge" :class="statusBadge(t.status)">{{ statusText(t.status) }}</span></td>
              <td class="mono small">{{ fmtNum(t.totalTokens) }}</td>
              <td class="small">{{ t.llmCallCount || 0 }} / {{ t.toolCallCount || 0 }}</td>
              <td class="mono small">{{ t.totalDurationMs }}ms</td>
              <td class="small secondary">查看 →</td>
            </tr>
          </tbody>
        </table>
      </div>
      <EmptyState v-else text="暂无对话链路记录" sub="发起一次对话后这里会出现完整的执行链路" />
    </div>

    <!-- 详情抽屉 -->
    <div v-if="detail" class="mask" @click.self="detail = null">
      <aside class="drawer card">
        <div class="drawer-head">
          <div>
            <div class="small muted mono">{{ detail.traceId }}</div>
            <h3 class="truncate">{{ detail.userQuery }}</h3>
          </div>
          <button class="btn btn-ghost btn-sm" @click="detail = null">关闭</button>
        </div>
        <div class="drawer-body">
          <div class="meta-grid">
            <div><div class="k">状态</div><span class="badge" :class="statusBadge(detail.status)">{{ statusText(detail.status) }}</span></div>
            <div><div class="k">耗时</div>{{ detail.totalDurationMs }}ms</div>
            <div><div class="k">Token</div>{{ fmtNum(detail.totalTokens) }}</div>
            <div><div class="k">意图</div>{{ INTENT[detail.intentType] || '—' }}</div>
            <div><div class="k">开始时间</div>{{ fmtTime(detail.startTime) }}</div>
            <div><div class="k">结束时间</div>{{ fmtTime(detail.endTime) }}</div>
          </div>
          <div v-if="detail.errorMessage" class="err-box">错误：{{ detail.errorMessage }}</div>
          <ToolCallCard :tool-calls="detail.toolCalls" :llm-calls="detail.llmCalls" />
          <EmptyState v-if="!detailLoading && !detail.toolCalls?.length && !detail.llmCalls?.length" text="无调用记录" />
          <div v-if="detailLoading" class="muted small">加载中…</div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.obs { display: flex; flex-direction: column; gap: 16px; max-width: 1280px; margin: 0 auto; }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.chart-card { padding: 16px; }
.chart { height: 240px; }
.table-card { padding: 16px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.title { font-weight: 600; }
.filters { display: flex; gap: 8px; }
.filters .select { width: 140px; padding: 6px 10px; }
.table-wrap { max-height: 420px; overflow: auto; }
.clickable { cursor: pointer; }
.clickable:hover td { color: var(--primary); }
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.35); z-index: 1500; display: flex; justify-content: flex-end; }
.drawer { width: 520px; max-width: 94vw; height: 100%; border-radius: 0; display: flex; flex-direction: column; animation: slide .2s ease; }
@keyframes slide { from { transform: translateX(100%); } to { transform: none; } }
.drawer-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding: 18px; border-bottom: 1px solid var(--border); }
.drawer-head h3 { margin-top: 4px; }
.drawer-body { flex: 1; overflow-y: auto; padding: 18px; }
.meta-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; margin-bottom: 12px; font-size: 13px; }
.meta-grid .k { color: var(--text-muted); font-size: 12px; margin-bottom: 2px; }
.err-box { background: #fef2f2; border: 1px solid #fecaca; color: var(--danger); padding: 8px 12px; border-radius: 6px; font-size: 12.5px; margin-bottom: 12px; }
</style>
