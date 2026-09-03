<script setup>
import { ref, computed, onMounted } from 'vue'
import * as hcApi from '../api/healthcheck'
import { useUiStore } from '../stores/ui'
import EmptyState from '../components/EmptyState.vue'

const ui = useUiStore()
const running = ref(false)
const result = ref(null)
const records = ref([])

const STATUS = {
  NORMAL: { text: '正常', cls: 'badge-success' },
  ANOMALY: { text: '异常', cls: 'badge-warning' },
  ERROR: { text: '错误', cls: 'badge-danger' }
}
const fmtTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 19) : '')
const bytes = (n) => {
  if (n == null) return '—'
  if (n < 1024) return `${n}B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}KB`
  return `${(n / 1024 / 1024).toFixed(2)}MB`
}

/* 每条记录是"每实例一行"，按 runId 分组 */
const grouped = computed(() => {
  const map = new Map()
  for (const r of records.value) {
    if (!map.has(r.runId)) map.set(r.runId, { runId: r.runId, checkedAt: r.checkedAt, rows: [] })
    map.get(r.runId).rows.push(r)
  }
  return [...map.values()]
})

const parseMetrics = (json) => {
  try {
    return JSON.parse(json || '[]')
  } catch (e) {
    return []
  }
}

onMounted(loadRecords)

async function run() {
  running.value = true
  result.value = null
  try {
    result.value = await hcApi.runHealthCheck()
    await loadRecords()
  } catch (e) {
    ui.error(e.message)
  } finally {
    running.value = false
  }
}

async function loadRecords() {
  try {
    records.value = await hcApi.getHealthRecords(50)
  } catch (e) {
    ui.error(e.message)
  }
}
</script>

<template>
  <div class="hc">
    <div class="card hero">
      <div class="hero-text">
        <h2>数据库健康巡检</h2>
        <p class="muted">基于目标实例关键指标自动巡检，发现异常触发告警与邮件通知</p>
      </div>
      <button class="btn btn-primary btn-lg" :disabled="running" @click="run">
        {{ running ? '巡检进行中…' : '开始巡检' }}
      </button>
    </div>

    <div v-if="running" class="card running-box">
      <div class="spinner" />
      <span>正在采集指标并调用大模型分析，请稍候…</span>
    </div>

    <template v-if="result">
      <div class="result-head">
        <span class="mono muted small">runId: {{ result.runId }}</span>
        <span v-if="result.message" class="muted">{{ result.message }}</span>
      </div>
      <div v-if="result.llmSummary" class="card summary-box">
        <div class="summary-title">AI 巡检总结</div>
        <div class="summary-text">{{ result.llmSummary }}</div>
      </div>
      <div v-if="result.details?.length" class="inst-grid">
        <div v-for="d in result.details" :key="d.instance" class="card inst">
          <div class="inst-head">
            <span class="inst-name">{{ d.instance }}</span>
            <span class="badge" :class="STATUS[d.status]?.cls">{{ STATUS[d.status]?.text || d.status }}</span>
          </div>
          <div v-if="d.status === 'ANOMALY' && d.anomalies?.length" class="anomalies">
            <div v-for="(a, i) in d.anomalies" :key="i" class="anomaly">
              <div class="metric">
                <span class="anomaly-title">{{ a.metric }}</span>
                <span class="anomaly-value mono">{{ a.value }} <span class="muted">(阈值 {{ a.threshold }})</span></span>
              </div>
              <div v-if="a.level" class="badge badge-danger small">级别 {{ a.level }}</div>
              <div v-if="a.suggestion" class="suggestion">建议：{{ a.suggestion }}</div>
            </div>
          </div>
          <div v-else-if="d.status === 'NORMAL'" class="ok-text">✓ 各项指标正常</div>
          <div v-else-if="d.error" class="muted small">采集失败：{{ d.error }}</div>
          <div v-if="d.emailSent" class="small muted">已发送告警邮件</div>
        </div>
      </div>
    </template>

    <div class="card table-card">
      <div class="title">历史巡检记录</div>
      <div v-if="grouped.length" class="groups">
        <div v-for="g in grouped" :key="g.runId" class="group card">
          <div class="group-head">
            <span class="mono small">{{ g.runId }}</span>
            <span class="muted small">{{ fmtTime(g.checkedAt) }}</span>
          </div>
          <table class="table">
            <tbody>
              <tr v-for="r in g.rows" :key="r.id">
                <td>{{ r.instanceName }}</td>
                <td><span class="badge" :class="STATUS[r.status]?.cls">{{ STATUS[r.status]?.text || r.status }}</span></td>
                <td class="small muted">
                  <template v-if="r.abnormalMetrics">
                    {{ parseMetrics(r.abnormalMetrics).map((m) => m.metric).join('、') || '—' }}
                  </template>
                  <template v-else>—</template>
                </td>
                <td class="small">{{ r.emailSent ? '已通知' : '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
      <EmptyState v-else text="暂无巡检记录" sub="点击上方「开始巡检」触发一轮巡检" />
    </div>
  </div>
</template>

<style scoped>
.hc { display: flex; flex-direction: column; gap: 16px; max-width: 1080px; margin: 0 auto; }
.hero {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 22px; background: linear-gradient(135deg, #eef2ff, #f5f3ff);
}
.hero h2 { font-size: 18px; margin-bottom: 4px; }
.btn-lg { padding: 10px 24px; font-size: 14px; }
.running-box { display: flex; align-items: center; gap: 12px; padding: 16px; color: var(--text-secondary); }
.spinner {
  width: 18px; height: 18px; border: 2px solid var(--primary-light); border-top-color: var(--primary);
  border-radius: 50%; animation: spin .7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.result-head { font-size: 12px; }
.summary-box { padding: 16px; }
.summary-title { font-weight: 600; margin-bottom: 8px; }
.summary-text { line-height: 1.7; white-space: pre-wrap; color: var(--text-secondary); }
.inst-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.inst { padding: 14px; }
.inst-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.inst-name { font-weight: 600; }
.anomalies { display: flex; flex-direction: column; gap: 8px; }
.anomaly { border: 1px solid #fecaca; background: #fff7f7; border-radius: 8px; padding: 8px 10px; }
.metric { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.anomaly-title { font-size: 13px; font-weight: 500; }
.anomaly-value { font-size: 12px; }
.suggestion { margin-top: 6px; font-size: 12px; color: var(--text-secondary); }
.ok-text { color: var(--success); font-size: 13px; }
.table-card { padding: 16px; }
.title { font-weight: 600; margin-bottom: 12px; }
.groups { display: flex; flex-direction: column; gap: 10px; }
.group { padding: 10px 12px; }
.group-head { display: flex; justify-content: space-between; margin-bottom: 4px; }
</style>
