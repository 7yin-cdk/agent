import axios from 'axios'

/*
 * 统一 axios 实例：注入 Bearer token；401 时清空登录态并跳转登录页。
 */
export const TOKEN_KEY = 'agent_token'
export const USER_KEY = 'agent_user'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setAuth(token, user) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

const http = axios.create({ baseURL: '', timeout: 30000 })

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (res) => res.data,
  (error) => {
    const status = error.response?.status
    if (status === 401 && !location.hash.startsWith('#/login')) {
      clearAuth()
      location.hash = '#/login'
    }
    const message = error.response?.data?.message
    return Promise.reject(new Error(message || error.message || '请求失败'))
  }
)

export default http
