<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], required: true },
  unit: { type: String, default: '' },
  hint: { type: String, default: '' }
})
</script>

<template>
  <div class="card stat">
    <div class="label">{{ label }}</div>
    <div class="value">
      {{ value }}<span v-if="unit" class="unit">{{ unit }}</span>
    </div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<style scoped>
.stat { padding: 16px 18px; }
.label { color: var(--text-secondary); font-size: 12.5px; }
.value { font-size: 26px; font-weight: 700; margin-top: 6px; color: var(--text); }
.unit { font-size: 13px; color: var(--text-muted); margin-left: 3px; font-weight: 400; }
.hint { margin-top: 6px; font-size: 12px; color: var(--text-muted); }
</style>
