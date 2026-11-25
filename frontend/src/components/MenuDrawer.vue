<script setup lang="ts">
// 로고를 누르면 왼쪽에서 밀려 나오는 사이드 팝업: 지금 캔버스 정보(정보 수정 → 공유 창) + 탭(홈 / 나의 캔버스)
// 닫기: 바깥 클릭, Esc(App), ✕
import { ref } from "vue";
import { useCanvas, roleOf, accessText, when, quarterOf, ROLE } from "../stores/canvas";
import Logo from "./Logo.vue";

const emit = defineEmits<{ close: []; edit: [] }>();
const cv = useCanvas();
const tab = ref<"home" | "mine">("mine");
function go(id: string) { emit("close"); cv.open(id); }
function home() { emit("close"); cv.goHome(); }
</script>

<template>
  <div class="drawer-bg" @click.self="emit('close')">
    <aside class="drawer" role="dialog" aria-label="메뉴">
      <header><Logo :size="24" /><b>Telescope</b><button class="x" aria-label="닫기" @click="emit('close')">✕</button></header>
      <section v-if="cv.meta" class="cur">
        <small>지금 캔버스</small>
        <b>{{ cv.canvasName }}</b>
        <span>{{ quarterOf(cv.meta) }} · {{ ROLE[cv.role!] }} · {{ accessText(cv.meta) }}</span>
        <button class="line" @click="emit('edit')">정보 수정</button>
      </section>
      <div class="tabs" role="tablist">
        <button role="tab" :aria-selected="tab === 'home'" @click="tab = 'home'">홈</button>
        <button role="tab" :aria-selected="tab === 'mine'" @click="tab = 'mine'">나의 캔버스 <small>{{ cv.canvases.length }}</small></button>
      </div>
      <div v-if="tab === 'home'" class="dlist">
        <button class="di" @click="home"><b>캔버스 홈으로</b><small>모든 캔버스 보기, 새 캔버스 만들기</small></button>
        <button class="di" @click="emit('close'); cv.creating = true"><b>+ 새 캔버스</b><small>이름, 분기, 함께 편집할 사람</small></button>
      </div>
      <ul v-else class="dlist">
        <li v-for="m in cv.canvases" :key="m.id">
          <button class="di" :aria-current="m.id === cv.canvasId ? 'page' : undefined" @click="go(m.id)">
            <b>{{ m.name }}</b><small>{{ ROLE[roleOf(m, cv.user)!] }} · {{ when(m.updated) }} 수정 · 카드 {{ m.count }}</small></button>
        </li>
      </ul>
    </aside>
  </div>
</template>
