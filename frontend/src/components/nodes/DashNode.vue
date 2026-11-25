<script setup lang="ts">
// 대시보드 카드: 자료 검색 대시보드 패널을 캔버스로 가져온 것. 시각화처럼 흰 블러 카드
import { computed } from "vue";
import { Handle, Position } from "@vue-flow/core";
import { useCanvas, type DashCard } from "../../stores/canvas";
import { panelOf } from "../../local/dashboard";
import DashPanel from "../DashPanel.vue";
import CardPrompt from "./CardPrompt.vue";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as DashCard);
const p = computed(() => panelOf(card.value?.panel ?? ""));
</script>

<template>
  <section v-if="card && p" class="card chart dash" :class="{ focus: cv.focus === card.id }">
    <Handle type="target" :position="Position.Left" /><Handle type="source" :position="Position.Right" />
    <header class="drag-handle"><span class="co">대시보드</span>
      <div class="t"><b>{{ p.title }}</b><small>{{ p.sub }} · DART 공시</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <DashPanel :panel="card.panel" />
    <CardPrompt :id="card.id" :text="card.prompt" />
  </section>
</template>
