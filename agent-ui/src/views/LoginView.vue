<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'

const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

const submit = async () => {
  if (!form.username || !form.password) return ui.error('请输入用户名和密码')
  loading.value = true
  try {
    await auth.login({ username: form.username, password: form.password })
    ui.success('登录成功')
    router.push('/')
  } catch (e) {
    ui.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <form class="card auth-card" @submit.prevent="submit">
      <div class="head">
        <div class="logo">DB Agent</div>
        <p class="sub">智能数据库运维控制台</p>
      </div>
      <div class="field">
        <label class="label" for="username">用户名</label>
        <input id="username" v-model="form.username" class="input" placeholder="请输入用户名" autocomplete="username" />
      </div>
      <div class="field">
        <label class="label" for="password">密码</label>
        <input id="password" v-model="form.password" type="password" class="input" placeholder="请输入密码" autocomplete="current-password" />
      </div>
      <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>
      <p class="switch">
        还没有账号？
        <router-link :to="{ name: 'register' }">立即注册</router-link>
      </p>
    </form>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  padding: 20px;
}
.auth-card { width: 380px; max-width: 100%; padding: 32px; }
.head { text-align: center; margin-bottom: 22px; }
.logo { font-size: 24px; font-weight: 700; color: var(--primary); }
.sub { color: var(--text-muted); margin-top: 6px; font-size: 13px; }
.field { margin-bottom: 16px; }
.switch { text-align: center; margin-top: 18px; font-size: 13px; color: var(--text-secondary); }
</style>
