<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useChatStore } from '../stores/chat'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const chat = useChatStore()

const TITLES = {
  chat: '对话工作台',
  knowledge: '知识库管理',
  memory: '长期记忆',
  observability: '可观测性',
  healthcheck: '健康巡检'
}
const title = computed(() => TITLES[route.name] || '')

const logout = async () => {
  chat.stop()
  await auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <header class="topbar">
    <h1 class="title">{{ title }}</h1>
    <div class="user">
      <span class="avatar">{{ (auth.displayName || '?').slice(0, 1).toUpperCase() }}</span>
      <span class="name">{{ auth.displayName }}</span>
      <button class="btn btn-ghost btn-sm" @click="logout">退出</button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: 56px; flex-shrink: 0; background: var(--surface); border-bottom: 1px solid var(--border);
  display: flex; align-items: center; justify-content: space-between; padding: 0 20px;
}
.title { font-size: 16px; font-weight: 600; }
.user { display: flex; align-items: center; gap: 10px; }
.avatar {
  width: 30px; height: 30px; border-radius: 50%; background: var(--primary-light); color: var(--primary);
  display: flex; align-items: center; justify-content: center; font-weight: 600; font-size: 13px;
}
.name { font-size: 13px; color: var(--text-secondary); }
</style>
