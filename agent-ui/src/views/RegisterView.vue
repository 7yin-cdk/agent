<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'

const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()
const form = reactive({ username: '', password: '', confirm: '', nickname: '' })
const loading = ref(false)

const submit = async () => {
  if (!form.username || !form.password) return ui.error('请输入用户名和密码')
  if (form.password !== form.confirm) return ui.error('两次输入的密码不一致')
  loading.value = true
  try {
    await auth.register({
      username: form.username.trim(),
      password: form.password,
      nickname: form.nickname?.trim() || undefined
    })
    ui.success('注册成功，已自动登录')
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
        <div class="logo">创建账号</div>
        <p class="sub">注册 DB Agent 运维控制台账号</p>
      </div>
      <div class="field">
        <label class="label">用户名</label>
        <input v-model="form.username" class="input" placeholder="请输入用户名" autocomplete="username" />
      </div>
      <div class="field">
        <label class="label">昵称（可选）</label>
        <input v-model="form.nickname" class="input" placeholder="展示昵称，默认同用户名" />
      </div>
      <div class="field">
        <label class="label">密码</label>
        <input v-model="form.password" type="password" class="input" placeholder="请输入密码" autocomplete="new-password" />
      </div>
      <div class="field">
        <label class="label">确认密码</label>
        <input v-model="form.confirm" type="password" class="input" placeholder="再次输入密码" autocomplete="new-password" />
      </div>
      <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
        {{ loading ? '注册中…' : '注册' }}
      </button>
      <p class="switch">
        已有账号？
        <router-link :to="{ name: 'login' }">去登录</router-link>
      </p>
    </form>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); padding: 20px;
}
.auth-card { width: 380px; max-width: 100%; padding: 32px; }
.head { text-align: center; margin-bottom: 22px; }
.logo { font-size: 22px; font-weight: 700; color: var(--primary); }
.sub { color: var(--text-muted); margin-top: 6px; font-size: 13px; }
.field { margin-bottom: 14px; }
.switch { text-align: center; margin-top: 18px; font-size: 13px; color: var(--text-secondary); }
</style>
