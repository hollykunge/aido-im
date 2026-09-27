import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // 接口转发到后端（server/，默认 8080）；后端在别的地址时设置 VITE_API_TARGET
    // ws: true 让 /api/realtime 的 WebSocket 也走代理
    proxy: { '/api': { target: process.env.VITE_API_TARGET || 'http://localhost:8080', ws: true } },
  },
})
