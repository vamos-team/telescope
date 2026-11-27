<script setup lang="ts">
// 시각화 카드: 고른 표 카드의 계정 대응 결과(build_compare, 서버에는 이 카드 id로 등록)를 계정별 가로 막대로
import { computed, ref, watch } from "vue";
import { api, type CompareResult } from "../../api";
import { useCanvas, type ChartCard } from "../../stores/canvas";
import CardMemo from "./CardMemo.vue";
import CardChrome from "./CardChrome.vue";
import SideHandles from "./SideHandles.vue";

defineOptions({ inheritAttrs: false });  // 장식(CardChrome) + 카드 두 뿌리라 Vue Flow 속성은 넘기지 않음
const props = defineProps<{ data: { cardId: string; onFlag?: (id: string) => void } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c): c is ChartCard => c.id === props.data.cardId && c.kind === "chart"));
const res = ref<CompareResult | null>(null);
const failed = ref(false);
watch([() => cv.sessionId, () => card.value?.id, () => card.value?.version], async ([sid, id], previous, onCleanup) => {
  let current = true;
  onCleanup(() => { current = false; });
  if (sid !== previous?.[0] || id !== previous?.[1]) res.value = null;
  failed.value = false;
  if (!id) return;
  const result = await api.compare(sid, id).catch(() => null);
  if (!current) return;
  res.value = result;
  failed.value = !result;
}, { immediate: true, flush: "sync" });

const shown = computed(() => (res.value?.lines ?? []).filter((l) => l.totals?.some((v) => v != null)));
const max = computed(() => Math.max(1, ...shown.value.flatMap((l) => (l.totals ?? []).map((v) => Math.abs(v ?? 0)))));
const fmt = (v: number | null | undefined) => v == null ? "—" : v.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
</script>

<template>
  <CardChrome v-if="card" :id="card.id" @flag="(id) => data.onFlag?.(id)" />
  <section v-if="card" class="card chart" :class="{ focus: cv.focus === card.id }">
    <SideHandles />
    <header class="drag-handle"><span class="co">시각화</span>
      <div class="t"><b v-if="res">{{ res.groups.map((g) => g.company).join(" · ") }} {{ res.lines.every((l) => l.why === "표 그대로") ? "계정별 값" : "계정 비교" }}</b><b v-else>계정 시각화</b><small>단위 십억원</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <p v-if="!res" class="cempty" role="status">{{ failed ? "비교 결과를 불러오지 못했습니다." : "비교 결과를 불러오는 중…" }}</p>
    <template v-else>
      <div class="legend2"><span v-for="(g, i) in res.groups" :key="g.company"><i :data-i="i" />{{ g.company }}</span></div>
      <p v-if="!shown.length" class="cempty">고른 표에 그릴 값이 없어요.</p>
      <div v-else class="bars nodrag nowheel">
        <div v-for="(l, i) in shown" :key="l.label + i" class="bl">
          <b>{{ l.label }}</b>
          <div v-for="(g, i) in res.groups" :key="g.company" class="br">
            <span class="n">{{ g.company }}</span>
            <span class="track"><span class="fill" :data-i="i" :class="{ neg: (l.totals?.[i] ?? 0) < 0 }"
              :style="{ width: (Math.abs(l.totals?.[i] ?? 0) / max * 100) + '%' }" /></span>
            <span class="v">{{ fmt(l.totals?.[i]) }}</span>
          </div>
        </div>
      </div>
    </template>
    <CardMemo :id="card.id" :text="card.memo" />
  </section>
</template>
