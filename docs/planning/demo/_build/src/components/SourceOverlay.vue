<script setup lang="ts">
// 원본 표 오버레이: 병합 셀 그대로 렌더링하고, 행 드래그/Shift+클릭으로 범위를 고른다.
import { ref, watch, computed } from "vue";
import { api, type SourceTable } from "../api";
import { useCanvas, type TableCard } from "../stores/canvas";

const props = defineProps<{ tableId: string | null; match: number[]; cardId?: string | null }>();
const cv = useCanvas();
const emit = defineEmits<{ close: []; add: [tableId: string, rows: number[], cols: number[]]; openNote: [noteNo: string] }>();
const t = ref<SourceTable | null>(null);
const sel = ref(new Set<number>()); const cols = ref(new Set<number>());
let anchor: number | null = null, dragging = false, mode = true, base = new Set<number>();

watch(() => props.tableId, async (id) => {
  anchor = null;  // 이전 표의 Shift+클릭 기준 행이 남지 않게
  if (!id) { t.value = null; return; }
  t.value = await api.table(id);
  const card = props.cardId ? cv.cards.find((c) => c.id === props.cardId) as TableCard : null;
  sel.value = new Set(card?.rows ?? props.match);
  cols.value = new Set(card?.cols ?? t.value.cols.map((_, i) => i));
}, { immediate: true });

const matchSet = computed(() => new Set(props.match));
function down(b: number, e: PointerEvent) {
  if (e.shiftKey && anchor !== null) { for (let i = Math.min(anchor, b); i <= Math.max(anchor, b); i++) sel.value.add(i); return; }
  dragging = true; anchor = b; mode = !sel.value.has(b); base = new Set(sel.value); over(b);
}
function over(b: number) {
  if (!dragging || anchor === null) return;
  const s = new Set(base);
  for (let i = Math.min(anchor, b); i <= Math.max(anchor, b); i++) mode ? s.add(i) : s.delete(i);
  sel.value = s;
}
function add() {
  if (!t.value || !sel.value.size || !cols.value.size) return;
  emit("add", t.value.table_id, [...sel.value].sort((a, b) => a - b), [...cols.value].sort((a, b) => a - b));
}
</script>

<template>
  <div v-if="t" class="scrim" @click.self="emit('close')" @pointerup="dragging = false">
    <div class="overlay" role="dialog" :aria-label="t.title">
      <header><h2>{{ t.company }} · {{ t.title }} <small>{{ t.period }}</small></h2>
        <p>{{ t.basis }} 기준 / 단위 {{ t.unit }} / 접수번호 {{ t.rcept_no }}</p>
        <button @click="emit('close')">닫기</button></header>
      <div v-if="!cardId" class="cols">가져올 열
        <label v-for="(c, i) in t.cols" :key="i"><input type="checkbox" :checked="cols.has(i)"
          @change="cols.has(i) ? cols.delete(i) : cols.add(i)" /> {{ c }}</label></div>
      <div class="body">
        <p v-for="c in t.caption" :key="c" class="cap">{{ c }}</p>
        <table class="raw"><tbody>
          <tr v-for="(row, r) in t.raw" :key="r" :class="{ b: r >= t.header_rows, s: sel.has(r - t.header_rows), m: matchSet.has(r - t.header_rows) }"
              @pointerdown="r >= t.header_rows && down(r - t.header_rows, $event)" @pointerover="r >= t.header_rows && over(r - t.header_rows)">
            <component :is="tag" v-for="[tag, v, rs, cs, c] in row" :key="c" :rowspan="rs > 1 ? rs : undefined" :colspan="cs > 1 ? cs : undefined"
              :class="{ lab: c < t.label_cols, off: c >= t.label_cols && !cols.has(c - t.label_cols) }">{{ v }}</component>
          </tr>
        </tbody></table>
      </div>
      <footer><span>선택 {{ sel.size }}행 × {{ cols.size }}열</span>
        <button @click="t.rows.forEach((_, i) => sel.add(i))">전체 행 선택</button>
        <button @click="sel.clear()">선택 해제</button>
        <button class="primary" @click="add">{{ cardId ? "카드에 반영" : "캔버스에 추가" }}</button></footer>
    </div>
  </div>
</template>
