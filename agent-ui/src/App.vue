<script setup>
import { storeToRefs } from 'pinia'
import { useUiStore } from './stores/ui'

const ui = useUiStore()
const { toasts } = storeToRefs(ui)
</script>

<template>
  <router-view />
  <div class="toast-wrap">
    <div v-for="t in toasts" :key="t.id" class="toast" :class="`toast-${t.type}`">
      {{ t.message }}
    </div>
  </div>
</template>

<style scoped>
.toast-wrap {
  position: fixed; top: 16px; right: 16px; z-index: 3000;
  display: flex; flex-direction: column; gap: 8px;
}
.toast {
  padding: 10px 16px; border-radius: var(--radius-sm); font-size: 13px;
  color: #fff; box-shadow: var(--shadow-lg); animation: slideIn .2s ease; max-width: 360px;
}
.toast-success { background: var(--success); }
.toast-error { background: var(--danger); }
.toast-info { background: #334155; }
@keyframes slideIn { from { transform: translateX(24px); opacity: 0; } to { transform: none; opacity: 1; } }
</style>
