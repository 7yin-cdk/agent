import { defineStore } from 'pinia'
import * as authApi from '../api/auth'
import { getToken, setAuth, clearAuth, USER_KEY } from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: getToken(),
    user: JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  }),
  getters: {
    isAuthed: (s) => !!s.token,
    displayName: (s) => s.user?.nickname || s.user?.username || ''
  },
  actions: {
    async login(payload) {
      const res = await authApi.login(payload)
      this.token = res.token
      this.user = res.user
      setAuth(res.token, res.user)
      return res
    },
    async register(payload) {
      const res = await authApi.register(payload)
      this.token = res.token
      this.user = res.user
      setAuth(res.token, res.user)
      return res
    },
    async logout() {
      try {
        await authApi.logout()
      } catch (e) {
        /* 登出接口失败也继续本地清理 */
      }
      this.token = ''
      this.user = null
      clearAuth()
    }
  }
})
