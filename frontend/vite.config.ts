import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// npm run dev: 실시간 편집 확인. base './': Electron이 dist/index.html을 파일로 열 수 있게 (nginx 루트 배포에도 그대로 동작)
// --mode html: docs/frontend/demo.html 한 파일 빌드 (scripts/inline.mjs가 합침)
export default defineConfig(({ mode }) => ({
  base: './',
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
