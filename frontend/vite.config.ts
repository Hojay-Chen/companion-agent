/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    // 五种 Surface 的测试跑在 node + renderToStaticMarkup 上, 不需要 jsdom:
    // 要断言的是"渲染出了什么结构", 而不是"点下去发生了什么" —— 后者是浏览器的事,
    // 而这里真正想钉住的是 SurfaceHost 对 manifest 的解释, 那是一段纯函数式的逻辑。
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
})
