import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
// npm run dev: 실시간 편집 확인. --mode demo: docs/planning/demo/index.html 한 파일용 정적 빌드 (scripts/inline.mjs가 합침)
export default defineConfig(({ mode }) => ({
  ...(mode === 'demo' && {
    base: './',
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
