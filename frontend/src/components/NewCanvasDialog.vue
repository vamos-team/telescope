<script setup lang="ts">
// 새 캔버스 창: 이름, 분기, 편집할 사람을 정해 만들고 바로 엶. 닫기는 ✕ · 취소 · Esc (빈 곳 클릭으로는 닫지 않음)
import { computed, ref } from "vue";
import { useCanvas, ALL_MEMBERS, QUARTERS, DEFAULT_QUARTER, findPeople } from "../stores/canvas";

const cv = useCanvas();
const name = ref(cv.defaultName()), quarter = ref(DEFAULT_QUARTER), editors = ref<string[]>([]);
const q = ref(""), focused = ref(false);
const found = computed(() => findPeople(q.value, new Set([cv.user!.id, ...editors.value]), cv.user!.dept));
const people = computed(() => editors.value.map((id) => ALL_MEMBERS.find((m) => m.id === id)!));
function add(id: string) { editors.value.push(id); q.value = ""; focused.value = false; }
function blur(e: FocusEvent) { if (!(e.relatedTarget as HTMLElement | null)?.closest(".found")) focused.value = false; }
const close = () => (cv.creating = false);
async function create() { close(); await cv.newCanvas({ name: name.value, quarter: quarter.value, editors: editors.value }); }
</script>

<template>
  <div class="scrim share-scrim" @keydown.esc="close">
    <form class="share newcv" role="dialog" aria-modal="true" aria-label="새 페이지" @submit.prevent="create">
      <header><h2>새 페이지</h2><button type="button" class="x" aria-label="닫기" @click="close">✕</button></header>

      <label class="fld">이름<input v-model="name" aria-label="페이지 이름" autofocus /></label>
      <label class="fld">기준 시점<select v-model="quarter" aria-label="기준 시점"><option v-for="x in QUARTERS" :key="x">{{ x }}</option></select></label>

      <h3>편집할 사람</h3>
      <div class="inv one">
        <input v-model="q" placeholder="사번, 이름, 부서로 사내 사람 찾기" aria-label="편집할 사람 찾기"
          @focus="focused = true" @click="focused = true" @input="focused = true" @blur="blur" @keydown.enter.prevent="found[0] && add(found[0].id)" />
        <ul v-if="focused" class="found">
          <li v-if="!q.trim() && found.length" class="cap">추천</li>
          <li v-for="m in found" :key="m.id"><button type="button" @mousedown.prevent @click="add(m.id)">
            <span class="av">{{ m.name[0] }}</span><b>{{ m.name }}</b><small>{{ m.id }} · {{ m.dept }} · {{ m.job }}</small></button></li>
          <li v-if="!found.length" class="none">찾는 사람이 없거나 이미 추가했습니다.</li>
        </ul>
      </div>
      <ul class="ppl">
        <li><span class="av" data-me="true">{{ cv.user!.name[0] }}</span>
          <span class="nm"><b>{{ cv.user!.name }} (나)</b><small>{{ cv.user!.dept }}</small></span><span class="rl">소유자</span></li>
        <li v-for="m in people" :key="m.id"><span class="av">{{ m.name[0] }}</span>
          <span class="nm"><b>{{ m.name }}</b><small>{{ m.dept }} · {{ m.job }}</small></span>
          <span class="rl">편집 가능</span>
          <button type="button" class="x" :aria-label="m.name + ' 빼기'" @click="editors = editors.filter((x) => x !== m.id)">✕</button></li>
      </ul>

      <footer><button type="button" class="line" @click="close">취소</button><button class="primary">만들기</button></footer>
    </form>
  </div>
</template>
