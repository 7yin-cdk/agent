import { defineStore } from 'pinia'

let toastSeq = 0

export const useUiStore = defineStore('ui', {
  state: () => ({
    toasts: []
  }),
  actions: {
    toast(message, type = 'info') {
      const id = ++toastSeq
      this.toasts.push({ id, message, type })
      setTimeout(() => {
        this.toasts = this.toasts.filter((t) => t.id !== id)
      }, 3000)
    },
    success(message) {
      this.toast(message, 'success')
    },
    error(message) {
      this.toast(message || '操作失败', 'error')
    }
  }
})
