<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
const items = [
  { name: 'chat', label: '对话', icon: 'M8 10h8m-8 4h5m-1.5-9H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h2v3l3-3h9a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-8.5z' },
  { name: 'knowledge', label: '知识库', icon: 'M5 4a2 2 0 0 1 2-2h6l4 4v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4zm6 0v5h5M7 14h6m-6 3h4' },
  { name: 'memory', label: '记忆', icon: 'M12 21s-7-4.6-9.3-9A5.6 5.6 0 0 1 12 5.6 5.6 5.6 0 0 1 21.3 12C19 16.4 12 21 12 21z' },
  { name: 'observability', label: '可观测', icon: 'M4 20V10m6 10V4m6 16v-7m4 7H2' },
  { name: 'healthcheck', label: '巡检', icon: 'M12 3l7 3v5c0 4.4-3 8.4-7 10-4-1.6-7-5.6-7-10V6l7-3zm-3 9l2 2 4-4' }
]
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="logo">DB</div>
      <div class="brand-text">
        <div class="title">Agent</div>
        <div class="sub">运维控制台</div>
      </div>
    </div>
    <nav class="nav">
      <router-link
        v-for="it in items"
        :key="it.name"
        :to="{ name: it.name }"
        class="nav-item"
        :class="{ active: route.name === it.name }"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path :d="it.icon" /></svg>
        <span>{{ it.label }}</span>
      </router-link>
    </nav>
    <div class="foot">v1.0 · DB Ops Agent</div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 216px; flex-shrink: 0; background: var(--sidebar-bg); color: var(--sidebar-text);
  display: flex; flex-direction: column; padding: 16px 12px;
}
.brand { display: flex; align-items: center; gap: 10px; padding: 4px 8px 18px; }
.logo {
  width: 36px; height: 36px; border-radius: 10px; background: linear-gradient(135deg, #6366f1, #8b5cf6);
  color: #fff; font-weight: 700; display: flex; align-items: center; justify-content: center;
}
.brand-text .title { color: #fff; font-weight: 600; font-size: 15px; line-height: 1.2; }
.brand-text .sub { font-size: 11px; color: var(--sidebar-muted); }
.nav { flex: 1; display: flex; flex-direction: column; gap: 2px; }
.nav-item {
  display: flex; align-items: center; gap: 10px; padding: 9px 10px; border-radius: var(--radius-sm);
  color: var(--sidebar-text); font-size: 13.5px; transition: background .15s ease;
}
.nav-item:hover { background: var(--sidebar-hover); }
.nav-item.active { background: var(--sidebar-active); color: #fff; }
.foot { padding: 12px 8px 2px; font-size: 11px; color: var(--sidebar-muted); border-top: 1px solid #1e293b; }
</style>
