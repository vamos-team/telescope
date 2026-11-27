import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// npm run dev: 실시간 편집 확인. base './': Electron이 dist/index.html을 파일로 열 수 있게 (nginx 루트 배포에도 그대로 동작)
// --mode html: docs/frontend/demo.html 한 파일 빌드 (scripts/inline.mjs가 합침)
export default defineConfig(({ mode }) => ({
  base: './',
  // Electron(electron/main.cjs)이 5173을 열므로 포트를 고정. 이미 쓰이면 다른 포트로 넘어가지 않고 멈춤
  // /api는 로컬 backend로 넘김(CORS 없이 같은 출처로 호출, 배포에서는 Ingress가 같은 일을 함). WebSocket(/api/v1/ws) 포함.
  // backend 주소는 DEV_API_TARGET으로 바꿀 수 있음
  server: { port: 5173, strictPort: true, proxy: { '/api': { target: process.env.DEV_API_TARGET ?? 'http://localhost:8080', ws: true } } },
  ...(mode === 'html' && {
    build: { cssCodeSplit: false, assetsInlineLimit: 1e8, rollupOptions: { output: { inlineDynamicImports: true } } },
  }),
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
}))
