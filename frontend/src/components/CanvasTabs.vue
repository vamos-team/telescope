<script setup lang="ts">
// 헤더 위 탭 줄: 맨 왼쪽 캔버스 홈, 지금 캔버스 이름(두 번 눌러 이름 바꾸기), 새 캔버스
import { computed, ref } from "vue";
import { useCanvas } from "../stores/canvas";

const cv = useCanvas();
const current = computed(() => cv.canvases.find((canvas) => canvas.id === cv.canvasId));
const editing = ref("");
const vFocus = { mounted: (el: HTMLInputElement) => el.select() };
function done(id: string, v: string) { if (id === cv.canvasId) cv.rename(v); editing.value = ""; }
</script>

<template>
  <nav class="ctabs" aria-label="캔버스 탭">
    <button class="hm" aria-label="캔버스 홈" title="캔버스 홈" @click="cv.goHome()">
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3.5 9 10 3.5 16.5 9v7.5h-4.5v-4.5h-4v4.5H3.5z" /></svg></button>
    <div v-if="current" class="ct on" aria-current="page">
      <input v-if="editing === current.id" :value="current.name" aria-label="캔버스 이름" v-focus
        @keydown.enter="done(current.id, ($event.target as HTMLInputElement).value)" @blur="done(current.id, ($event.target as HTMLInputElement).value)" />
      <button v-else class="nm" :title="cv.readOnly ? '' : '두 번 눌러 이름 바꾸기'" @dblclick="!cv.readOnly && (editing = current.id)">{{ current.name }}</button>
    </div>
    <button class="add" aria-label="새 캔버스" title="새 캔버스" @click="cv.newCanvas()">+</button>
  </nav>
</template>
