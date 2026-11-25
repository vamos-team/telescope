<script setup lang="ts">
// 헤더 위 탭 줄: 지금 캔버스 이름(두 번 눌러 이름 바꾸기)과 새 캔버스. 캔버스 목록은 왼쪽 사이드바 맨 위 아이콘에서 (팝업 없음)
import { computed, ref } from "vue";
import { useCanvas } from "../stores/canvas";

const cv = useCanvas();
const current = computed(() => cv.canvases.find((canvas) => canvas.id === cv.canvasId));
const editing = ref("");
const vFocus = { mounted: (el: HTMLInputElement) => el.select() };
function done(id: string, v: string) { if (id === cv.canvasId) cv.rename(v); editing.value = ""; }
</script>

<template>
  <nav class="ctabs" :aria-label="cv.user!.dept + ' 캔버스'">
    <span class="dept">{{ cv.user!.dept }}</span>
    <div v-if="current" class="ct on" aria-current="page">
      <input v-if="editing === current.id" :value="current.name" aria-label="캔버스 이름" v-focus
        @keydown.enter="done(current.id, ($event.target as HTMLInputElement).value)" @blur="done(current.id, ($event.target as HTMLInputElement).value)" />
      <button v-else class="nm" title="두 번 눌러 이름 바꾸기" @dblclick="editing = current.id">{{ current.name }}</button>
    </div>
    <button class="add" aria-label="새 캔버스" title="새 캔버스" @click="cv.newCanvas()">+</button>
  </nav>

</template>
