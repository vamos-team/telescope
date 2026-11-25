<script setup lang="ts">
// 시각화 카드: 비교 카드의 계산 결과(build_compare)를 계정별 가로 막대로. 원본 비교 카드가 다시 계산되면 따라 그린다
import { Handle, Position } from "@vue-flow/core";
import { computed, ref, watch } from "vue";
import { api, type CompareResult } from "../../api";
import { useCanvas, type ChartCard, type CompareCard } from "../../stores/canvas";
import CardPrompt from "./CardPrompt.vue";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as ChartCard);
const cmp = computed(() => cv.cards.find((c) => c.id === card.value?.compareId) as CompareCard | undefined);
const res = ref<CompareResult | null>(null);
watch(() => cmp.value?.version, async () => { if (cmp.value) res.value = await api.compare(cv.sessionId, cmp.value.id).catch(() => res.value); }, { immediate: true });

const off = ref(new Set<string>());  // 숨긴 계정
const lines = computed(() => (res.value?.lines ?? []).filter((l) => l.totals?.some((v) => v != null)));
const shown = computed(() => lines.value.filter((l) => !off.value.has(l.label)));
const max = computed(() => Math.max(1, ...shown.value.flatMap((l) => (l.totals ?? []).map((v) => Math.abs(v ?? 0)))));
const toggle = (k: string) => { const s = new Set(off.value); s.has(k) ? s.delete(k) : s.add(k); off.value = s; };
const fmt = (v: number | null | undefined) => v == null ? "—" : v.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
</script>

<template>
  <section v-if="res" class="card chart" :class="{ focus: cv.focus === card.id }">
    <Handle type="target" :position="Position.Left" />
    <header class="drag-handle"><span class="co">시각화</span>
      <div class="t"><b>{{ res.groups.map((g) => g.company).join(" · ") }} 계정 비교</b><small>단위 십억원</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <div class="chips nodrag">
      <button v-for="l in lines" :key="l.label" :aria-pressed="!off.has(l.label)" @click="toggle(l.label)">{{ l.label }}</button>
    </div>
    <div class="legend2"><span v-for="(g, i) in res.groups" :key="g.company"><i :data-i="i" />{{ g.company }}</span></div>
    <div class="bars nodrag nowheel">
      <div v-for="l in shown" :key="l.label" class="bl">
        <b>{{ l.label }}</b>
        <div v-for="(g, i) in res.groups" :key="g.company" class="br">
          <span class="n">{{ g.company }}</span>
          <span class="track"><span class="fill" :data-i="i" :class="{ neg: (l.totals?.[i] ?? 0) < 0 }"
            :style="{ width: (Math.abs(l.totals?.[i] ?? 0) / max * 100) + '%' }" /></span>
          <span class="v">{{ fmt(l.totals?.[i]) }}</span>
        </div>
      </div>
    </div>
    <CardPrompt :id="card.id" :text="card.prompt" />
  </section>
</template>
