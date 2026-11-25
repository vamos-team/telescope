<script setup lang="ts">
import { computed, ref } from "vue";
import { useCanvas } from "../stores/canvas";

const cv = useCanvas();
const list = ref<HTMLDialogElement | null>(null);
const current = computed(() => cv.canvases.find((canvas) => canvas.id === cv.canvasId));
const editing = ref("");
const vFocus = { mounted: (el: HTMLInputElement) => el.select() };
function done(id: string, v: string) { if (id === cv.canvasId) cv.rename(v); editing.value = ""; }
function del(id: string, name: string) { if (confirm(`'${name}' 캔버스를 지울까요? 부서원 모두에게서 지워집니다.`)) cv.removeCanvas(id); }
async function selectCanvas(id: string) {
  await cv.open(id);
  list.value?.close();
}
</script>

<template>
  <nav class="ctabs" :aria-label="cv.user!.dept + ' 캔버스'">
    <button class="canvas-list-trigger" aria-haspopup="dialog" aria-controls="department-canvases" @click="list?.showModal()">
      <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 5h10M7 10h10M7 15h10M3 5h1M3 10h1M3 15h1" /></svg>
      목록 보기
    </button>
    <span class="dept">{{ cv.user!.dept }}</span>
    <div v-if="current" class="ct on" aria-current="page">
      <input v-if="editing === current.id" :value="current.name" aria-label="캔버스 이름" v-focus
        @keydown.enter="done(current.id, ($event.target as HTMLInputElement).value)" @blur="done(current.id, ($event.target as HTMLInputElement).value)" />
      <button v-else class="nm" title="두 번 눌러 이름 바꾸기" @dblclick="editing = current.id">{{ current.name }}</button>
    </div>
    <button class="add" aria-label="새 캔버스" title="새 캔버스" @click="cv.newCanvas()">+</button>
  </nav>

  <dialog id="department-canvases" ref="list" class="canvas-drawer" aria-labelledby="canvas-list-title" @click.self="list?.close()">
    <div class="canvas-drawer-content">
      <header>
        <div>
          <h2 id="canvas-list-title">부서 캔버스</h2>
          <small>{{ cv.user!.dept }} · {{ cv.canvases.length }}개</small>
        </div>
        <button class="sbx" aria-label="목록 닫기" autofocus @click="list?.close()">✕</button>
      </header>
      <ul>
        <li v-for="canvas in cv.canvases" :key="canvas.id" :aria-current="canvas.id === cv.canvasId ? 'page' : undefined">
          <button class="canvas-entry" @click="selectCanvas(canvas.id)">
            <b>{{ canvas.name }}</b>
            <small>카드 {{ canvas.count }} · {{ canvas.updatedBy }} 수정</small>
          </button>
          <button class="sbx canvas-delete" :aria-label="canvas.name + ' 지우기'" @click="del(canvas.id, canvas.name)">✕</button>
        </li>
      </ul>
    </div>
  </dialog>
</template>
