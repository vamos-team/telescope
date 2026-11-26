<script setup lang="ts">
// 비교 카드: 계산은 서버(build_compare)에서. 이벤트가 반영될 때마다(version) 다시 가져온다.
import { computed, ref, watch } from "vue";
import { api, type CompareResult, type CompareLine } from "../../api";
import { useCanvas, type CompareCard, type TableCard } from "../../stores/canvas";
import CardMemo from "./CardMemo.vue";
import CardChrome from "./CardChrome.vue";
import SideHandles from "./SideHandles.vue";
import type { SourceTable } from "../../api";

defineOptions({ inheritAttrs: false });  // 장식(CardChrome) + 카드 두 뿌리라 Vue Flow 속성은 넘기지 않음
const props = defineProps<{ data: { cardId: string; onFlag?: (id: string) => void } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c): c is CompareCard => c.id === props.data.cardId && c.kind === "compare"));
const res = ref<CompareResult | null>(null);
// 카드 생성 직후에는 이벤트가 아직 전송 전(150ms 묶음)이라 서버에 카드가 없다. 전송 뒤 version이 올라가면 다시 받는다
watch([() => cv.sessionId, () => card.value?.id, () => card.value?.version], async ([sid, id], previous, onCleanup) => {
  let current = true;
  onCleanup(() => { current = false; });
  if (sid !== previous?.[0] || id !== previous?.[1]) res.value = null;
  if (!id) return;
  const result = await api.compare(sid, id).catch(() => res.value);
  if (current) res.value = result;
}, { immediate: true, flush: "sync" });

// 원본 카드별 비교 열 선택 (원본 카드의 select_period와 같은 이벤트)
const srcTables = ref<Record<string, SourceTable>>({});
const sources = computed(() => (card.value?.sources ?? []).map((id) => cv.cards.find((c): c is TableCard => c.id === id && c.kind === "table")).filter((c) => c !== undefined));
watch(sources, async (ss) => { for (const s of ss) srcTables.value[s.tableId] ??= await api.table(s.tableId); }, { immediate: true });
const fmt = (v: number | null | undefined) => {
  if (v == null) return "—";
  const s = Math.abs(v).toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return v < 0 ? `(${s})` : s;
};
const tip = (l: CompareLine, i: number) => (l.values[i] ?? []).map((p) => `${p.label} [${p.cell_id}]`).join(" + ");
const diff = (l: CompareLine) => l.totals && l.totals[0] != null && l.totals[1] != null ? l.totals[1]! - l.totals[0]! : null;
// 확정 ✓ / 거부 ✗. 이미 확정한 것을 다시 누르면 취소. 거부한 행은 "대응 없음"으로 내려가고, 거기서 되돌림
function confirm(l: CompareLine, ok: boolean) {
  const undo = ok && l.confirmed;
  res.value?.groups.forEach((g, i) => l.values[i] && cv.confirmMapping(l.concept_id!, g.company, l.values[i]!.map((p) => p.cell_id), undo ? null : ok));
}
const restore = (l: CompareLine) => res.value?.groups.forEach((g) => cv.confirmMapping(l.concept_id!, g.company, [], null));
const plain = computed(() => !!res.value?.lines.length && res.value.lines.every((l) => l.why === "표 그대로"));
const showDecisions = computed(() => !plain.value || res.value?.unmatched.some((l) => l.why === "거부한 대응"));
const sub = computed(() => res.value ? [new Set(res.value.groups.map((g) => g.basis)).size > 1 ? "연결 · 별도 섞임" : res.value.groups[0]?.basis, "단위 십억원"].filter(Boolean).join(" · ") : "");
</script>

<template>
  <CardChrome v-if="card" :id="card.id" @flag="(id) => data.onFlag?.(id)" />
  <section v-if="card && res" class="card cmp" :class="{ focus: cv.focus === card.id }">
    <SideHandles />
    <header class="drag-handle"><span class="co">비교</span>
      <div class="t"><b>{{ plain ? "표 모음" : "계정 대응 비교" }}: {{ res.groups.map((g) => g.company + " " + g.basis).join(" vs ") }}</b>
        <small>{{ sub }}</small></div>
      <button class="nodrag txt" @click="cv.addChart(card.id)" title="비교 결과를 막대그래프 카드로">시각화</button>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <div class="periods nodrag">
      <label v-for="s in sources" :key="s.id"><span>{{ srcTables[s.tableId]?.company }} {{ srcTables[s.tableId]?.title }}</span>
        <select :value="s.vcol" @change="cv.setPeriod(s.id, +($event.target as HTMLSelectElement).value)">
          <option v-for="c in s.cols" :key="c" :value="c">{{ srcTables[s.tableId]?.cols[c] }}</option></select></label>
    </div>
    <div class="tw nodrag nowheel"><table class="g">
      <thead><tr><th>계정 (근거)</th><th v-for="g in res.groups" :key="g.company + g.basis">{{ g.company }}</th>
        <th v-if="res.groups.length === 2">{{ res.groups[1].company }} − {{ res.groups[0].company }}</th><th v-if="showDecisions">대응</th></tr></thead>
      <tbody>
        <tr v-for="(l, i) in res.lines" :key="l.label + i" :class="{ okrow: l.confirmed }">
          <td :title="l.note ?? ''">{{ l.label }} <small v-if="l.why !== '표 그대로'">{{ l.why }}</small></td>
          <td v-for="(_, i) in res.groups" :key="i" :title="tip(l, i)">{{ fmt(l.totals?.[i]) }}<small v-if="(l.values[i]?.length ?? 0) > 1"> Σ{{ l.values[i]!.length }}</small></td>
          <td v-if="res.groups.length === 2">{{ fmt(diff(l)) }}</td>
          <td v-if="showDecisions" class="cf"><template v-if="l.concept_id"><button :aria-pressed="!!l.confirmed" :title="l.confirmed ? '확정 취소' : '대응 확정'" @click="confirm(l, true)">✓</button><button title="대응 거부 (대응 없음으로 내림)" @click="confirm(l, false)">✗</button></template>
            <small v-else-if="l.why === '동일 계정명'">이름 같음</small></td>
        </tr>
        <tr v-if="res.unmatched.length" class="grp"><td :colspan="1 + res.groups.length + Number(res.groups.length === 2) + Number(showDecisions)">대응 없음 — 한쪽 회사에만 있는 계정 ({{ res.unmatched.length }})</td></tr>
        <tr v-for="(l, i) in res.unmatched" :key="'u' + l.label + i" :class="{ rej: l.why === '거부한 대응' }"><td>{{ l.label }} <small v-if="l.why === '거부한 대응'">거부함</small></td>
          <td v-for="(_, i) in res.groups" :key="i">{{ fmt(l.values[i]?.[0]?.value_bil) }}</td>
          <td v-if="res.groups.length === 2" />
          <td v-if="showDecisions" class="cf"><button v-if="l.why === '거부한 대응' && l.concept_id" title="거부 취소" @click="restore(l)">되돌리기</button></td></tr>
      </tbody></table></div>
    <footer v-if="plain">공통 계정이 없어 고른 표의 행을 그대로 모았어요 · {{ res.lines.length }}행</footer>
    <footer v-else>대응 {{ res.lines.length }}개 · 확정 {{ res.lines.filter((l) => l.confirmed).length }}개 · Σ는 여러 계정 합산 · 셀에 마우스를 올리면 구성 계정</footer>
    <CardMemo :id="card.id" :text="card.memo" />
  </section>
</template>
