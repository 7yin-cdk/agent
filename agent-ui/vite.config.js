import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/*
 * Agent 前端单页控制台构建配置。
 * 生产构建产物直接写入 Spring Boot 的 src/main/resources/static，
 * index.html 即 SPA 入口（hash 路由，无需服务端 history 回退）。
 * dev 模式下将 /agent /auth /healthcheck 代理到后端 8084。
 */
export default defineConfig({
  plugins: [vue()],
  base: '/',
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    assetsDir: 'assets'
  },
  server: {
    port: 5173,
    proxy: {
      '/agent': { target: 'http://localhost:8084', changeOrigin: true },
      '/auth': { target: 'http://localhost:8084', changeOrigin: true },
      '/healthcheck': { target: 'http://localhost:8084', changeOrigin: true }
    }
  }
})
