<script setup lang="ts">
// 비교 카드: 계산은 서버(build_compare)에서. 이벤트가 반영될 때마다(version) 다시 가져온다.
import { Handle, Position } from "@vue-flow/core";
import { computed, ref, watch } from "vue";
import { api, type CompareResult, type CompareLine } from "../../api";
import { useCanvas, type CompareCard, type TableCard } from "../../stores/canvas";
import CardPrompt from "./CardPrompt.vue";
import type { SourceTable } from "../../api";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as CompareCard);
const res = ref<CompareResult | null>(null);
// 카드 생성 직후에는 이벤트가 아직 전송 전(150ms 묶음)이라 서버에 카드가 없다. 전송 뒤 version이 올라가면 다시 받는다
watch(() => card.value?.version, async () => { res.value = await api.compare(cv.sessionId, card.value.id).catch(() => res.value); }, { immediate: true });

// 원본 카드별 비교 열 선택 (원본 카드의 select_period와 같은 이벤트)
const srcTables = ref<Record<string, SourceTable>>({});
const sources = computed(() => card.value.sources.map((id) => cv.cards.find((c) => c.id === id) as TableCard).filter(Boolean));
watch(sources, async (ss) => { for (const s of ss) srcTables.value[s.tableId] ??= await api.table(s.tableId); }, { immediate: true });
const fmt = (v: number | null | undefined) => {
  if (v == null) return "—";
  const s = Math.abs(v).toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return v < 0 ? `(${s})` : s;
};
const tip = (l: CompareLine, i: number) => (l.values[i] ?? []).map((p) => `${p.label} [${p.cell_id}]`).join(" + ");
const diff = (l: CompareLine) => l.totals && l.totals[0] != null && l.totals[1] != null ? l.totals[1]! - l.totals[0]! : null;
function confirm(l: CompareLine, ok: boolean) {
  res.value?.groups.forEach((g, i) => l.values[i] && cv.confirmMapping(l.concept_id!, g.company, l.values[i]!.map((p) => p.cell_id), ok));
}
</script>

<template>
  <section v-if="res" class="card cmp" :class="{ focus: cv.focus === card.id }">
    <Handle type="target" :position="Position.Left" />
    <header class="drag-handle"><span class="co">비교</span>
      <div class="t"><b>계정 대응 비교: {{ res.groups.map((g) => g.company + " " + g.basis).join(" vs ") }}</b>
        <small>단위 십억원</small></div>
      <button class="nodrag txt" @click="cv.addChart(card.id)" title="비교 결과를 막대그래프 카드로">시각화</button>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <div class="periods nodrag">
      <label v-for="s in sources" :key="s.id"><span>{{ srcTables[s.tableId]?.company }} {{ srcTables[s.tableId]?.title }}</span>
        <select :value="s.vcol" @change="cv.setPeriod(s.id, +($event.target as HTMLSelectElement).value)">
          <option v-for="c in s.cols" :key="c" :value="c">{{ srcTables[s.tableId]?.cols[c] }}</option></select></label>
    </div>
    <ul class="warn"><li v-for="w in res.warnings" :key="w">{{ w }}</li></ul>
    <div class="tw nodrag nowheel"><table class="g">
      <thead><tr><th>계정 (근거)</th><th v-for="g in res.groups" :key="g.company + g.basis">{{ g.company }}</th>
        <th v-if="res.groups.length === 2">{{ res.groups[1].company }} − {{ res.groups[0].company }}</th><th>확정</th></tr></thead>
      <tbody>
        <tr v-for="l in res.lines" :key="l.label">
          <td :title="l.note ?? ''">{{ l.label }} <small>{{ l.why }}</small></td>
          <td v-for="(_, i) in res.groups" :key="i" :title="tip(l, i)">{{ fmt(l.totals?.[i]) }}<small v-if="(l.values[i]?.length ?? 0) > 1"> Σ{{ l.values[i]!.length }}</small></td>
          <td v-if="res.groups.length === 2">{{ fmt(diff(l)) }}</td>
          <td><template v-if="l.concept_id"><button @click="confirm(l, true)" title="대응 확정">✓</button><button @click="confirm(l, false)" title="대응 거부">✗</button></template></td>
        </tr>
        <tr class="grp"><td :colspan="res.groups.length + 3">대응 없음 — 한쪽 회사에만 있는 계정 ({{ res.unmatched.length }})</td></tr>
        <tr v-for="l in res.unmatched" :key="'u' + l.label"><td>{{ l.label }}</td>
          <td v-for="(_, i) in res.groups" :key="i">{{ fmt(l.values[i]?.[0]?.value_bil) }}</td></tr>
      </tbody></table></div>
    <footer>대응 {{ res.lines.length }}개 · Σ는 여러 계정을 합산한 값 · 셀에 마우스를 올리면 구성 계정이 보입니다</footer>
    <CardPrompt :id="card.id" :text="card.prompt" />
  </section>
</template>
