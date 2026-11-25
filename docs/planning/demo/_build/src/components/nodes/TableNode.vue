<script setup lang="ts">
// 캔버스 위 원본 슬라이스 카드. 원본 값은 store의 SourceTable, 편집은 card.edits 레이어.
import { Handle, Position } from "@vue-flow/core";
import { computed, ref, onMounted } from "vue";
import { api, UNIT_TO_BIL, type SourceTable } from "../../api";
import { useCanvas, type TableCard } from "../../stores/canvas";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as TableCard);
const t = ref<SourceTable | null>(null);
onMounted(async () => { t.value = await api.table(card.value.tableId); });

const val = (r: number, c: number) => {
  const k = `${r},${c}`;
  return k in card.value.edits ? card.value.edits[k] : t.value!.rows[r].values[c];
};
const rows = computed(() => {
  const rs = [...card.value.rows]; const s = card.value.sort; if (!s || !t.value) return rs;
  const get = (r: number) => (s.key === "label" ? t.value!.rows[r].label : val(r, s.key as number));
  return rs.sort((a, b) => { const x = get(a), y = get(b); const d = typeof x === "string" ? x.localeCompare(y as string, "ko") : (x ?? 0) - ((y as number) ?? 0); return s.desc ? -d : d; });
});
function cycleSort(key: string | number) {
  const s = card.value.sort;
  if (!s || s.key !== key) cv.sortCard(card.value.id, key, key !== "label");
  else if (s.desc === (key !== "label")) cv.sortCard(card.value.id, key, !s.desc);
  else cv.sortCard(card.value.id, null);
}
// 표시 단위: 원본 그대로 또는 십억원 환산 (편집은 원본 단위로 되돌려 저장)
const factor = computed(() => cv.unit === "bil" && t.value ? UNIT_TO_BIL[t.value.unit] ?? 1 : 1);
const unitLabel = computed(() => cv.unit === "bil" ? "십억원" : t.value?.unit);
const fmt = (v: number | null | undefined) => {
  if (v == null) return "";
  const x = Math.abs(v * factor.value), s = cv.unit === "raw" ? x.toLocaleString("ko-KR")
    : x.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return v < 0 ? `(${s})` : s;
};
const noteId = (n: string) => api.noteTable(t.value!.rcept_no, n);
function edit(r: number, c: number, e: Event) {
  const s = (e.target as HTMLElement).innerText.trim();
  const neg = /^\(.*\)$/.test(s) || s.startsWith("-");
  const n = s ? parseFloat(s.replace(/[(),\s-]/g, "")) : null;
  if (n === null || !Number.isNaN(n)) cv.editCell(card.value.id, r, c, n === null ? null : (neg ? -n : n) / factor.value);
}
</script>

<template>
  <section v-if="t" class="card" :data-co="t.company" :class="{ focus: cv.focus === card.id }">
    <Handle type="target" :position="Position.Left" /><Handle type="source" :position="Position.Right" />
    <header class="drag-handle">
      <span class="co">{{ t.company }}</span><div class="t"><b>{{ t.title }}</b><small>{{ t.basis }} / {{ t.period || t.rcept_no }}</small></div>
      <label class="nodrag"><input v-model="card.checked" type="checkbox" /> 비교</label>
      <button class="nodrag txt" @click="cv.openSource(card.tableId, [], card.id)" title="원본 표에서 행 고르기">원본</button>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button>
    </header>
    <div class="tw nodrag nowheel">
      <table class="g"><thead><tr>
        <th @click="cycleSort('label')">계정</th>
        <th v-for="c in card.cols" :key="c" @click="cycleSort(c)">{{ t.cols[c] }}</th>
      </tr></thead><tbody>
        <tr v-for="r in rows" :key="r">
          <td :style="{ paddingLeft: 8 + t.rows[r].depth * 12 + 'px' }">{{ t.rows[r].label }}<small v-if="t.rows[r].grp"> {{ t.rows[r].grp }}</small>
            <template v-for="n in t.rows[r].notes" :key="n"><button v-if="noteId(n)" class="note"
              @click="cv.openSource(noteId(n))">주{{ n }}</button></template>
            <button class="rm" @click="cv.removeRow(card.id, r)" aria-label="행 제거">✕</button></td>
          <td v-for="c in card.cols" :key="c" class="v" :class="{ edited: `${r},${c}` in card.edits }" contenteditable
              :title="`${r},${c}` in card.edits ? '원본: ' + fmt(t.rows[r].values[c]) : ''" @blur="edit(r, c, $event)">{{ fmt(val(r, c)) }}</td>
        </tr>
      </tbody></table>
    </div>
    <footer>단위 {{ unitLabel }} · {{ card.rows.length }}행
      <select class="nodrag" :value="card.vcol" @change="cv.setPeriod(card.id, +($event.target as HTMLSelectElement).value)">
        <option v-for="c in card.cols" :key="c" :value="c">비교 열: {{ t.cols[c] }}</option></select></footer>
  </section>
</template>
