import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../api/http'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  { path: '/register', name: 'register', component: () => import('../views/RegisterView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../components/AppShell.vue'),
    children: [
      { path: '', redirect: '/chat' },
      { path: 'chat', name: 'chat', component: () => import('../views/ChatView.vue') },
      { path: 'knowledge', name: 'knowledge', component: () => import('../views/KbView.vue') },
      { path: 'memory', name: 'memory', component: () => import('../views/MemoryView.vue') },
      { path: 'observability', name: 'observability', component: () => import('../views/ObservabilityView.vue') },
      { path: 'healthcheck', name: 'healthcheck', component: () => import('../views/HealthcheckView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to) => {
  const authed = !!getToken()
  if (!to.meta.public && !authed) return { name: 'login' }
  if (to.meta.public && authed) return { path: '/' }
  return true
})

export default router
