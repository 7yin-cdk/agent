<script setup>
defineProps({
  text: { type: String, default: '暂无数据' },
  sub: { type: String, default: '' }
})
</script>

<template>
  <div class="empty">
    <svg viewBox="0 0 24 24" width="42" height="42" fill="none" stroke="currentColor" stroke-width="1.2">
      <path d="M4 7h16M4 12h16M4 17h10" stroke-linecap="round" />
    </svg>
    <div>{{ text }}</div>
    <div v-if="sub" class="small">{{ sub }}</div>
  </div>
</template>
