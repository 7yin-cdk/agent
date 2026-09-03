<script setup>
const props = defineProps({
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  danger: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm', 'cancel'])
</script>

<template>
  <div class="mask" @click.self="emit('cancel')">
    <div class="dialog card">
      <h3>{{ title }}</h3>
      <p class="message">{{ message }}</p>
      <div class="actions">
        <button class="btn btn-ghost" @click="emit('cancel')">取消</button>
        <button class="btn" :class="danger ? 'btn-danger' : 'btn-primary'" @click="emit('confirm')">
          {{ confirmText }}
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed; inset: 0; background: rgba(15, 23, 42, 0.4); z-index: 2000;
  display: flex; align-items: center; justify-content: center;
}
.dialog { width: 360px; max-width: calc(100vw - 40px); padding: 20px; }
.dialog h3 { font-size: 15px; margin-bottom: 10px; }
.message { color: var(--text-secondary); font-size: 13px; line-height: 1.6; margin-bottom: 18px; word-break: break-word; }
.actions { display: flex; justify-content: flex-end; gap: 10px; }
</style>
