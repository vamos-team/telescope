import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
// 정적 빌드: JS·CSS를 한 덩어리로 만든 뒤 inline.mjs가 ../index.html 하나로 합친다.
export default defineConfig({
  plugins: [vue()],
  base: "./",
  build: { cssCodeSplit: false, assetsInlineLimit: 1e8, rollupOptions: { output: { inlineDynamicImports: true } } },
});
