<script setup lang="ts">
// 대시보드 카드: 자료 검색 대시보드 패널을 캔버스로 가져온 것. 시각화처럼 흰 블러 카드
import { computed } from "vue";
import { useCanvas, type DashCard } from "../../stores/canvas";
import { panelOf } from "../../local/dashboard";
import DashPanel from "../DashPanel.vue";
import CardMemo from "./CardMemo.vue";
import CardChrome from "./CardChrome.vue";
import SideHandles from "./SideHandles.vue";

defineOptions({ inheritAttrs: false });  // 장식(CardChrome) + 카드 두 뿌리라 Vue Flow 속성은 넘기지 않음
const props = defineProps<{ data: { cardId: string; onFlag?: (id: string) => void } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as DashCard);
const p = computed(() => panelOf(card.value?.panel ?? ""));
</script>

<template>
  <CardChrome v-if="card" :id="card.id" @flag="(id) => data.onFlag?.(id)" />
  <section v-if="card && p" class="card chart dash" :class="{ focus: cv.focus === card.id }">
    <SideHandles />
    <header class="drag-handle"><span class="co">대시보드</span>
      <div class="t"><b>{{ p.title }}</b><small>{{ [p.sub, "DART 공시"].filter(Boolean).join(" · ") }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <DashPanel :panel="card.panel" compact />
    <CardMemo :id="card.id" :text="card.memo" />
  </section>
</template>
