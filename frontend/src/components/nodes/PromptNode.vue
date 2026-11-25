<script setup lang="ts">
// 프롬프트 카드: 생성(결과물·질문)에 줄 지시. 다른 카드와 선으로 이어 "이 근거에 대한 지시"로 쓸 수 있음. 사실 근거로는 쓰지 않음
import { computed } from "vue";
import { Handle, Position } from "@vue-flow/core";
import { useCanvas, type PromptCard } from "../../stores/canvas";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as PromptCard);
const save = (e: Event) => { const t = (e.target as HTMLTextAreaElement).value.trim(); if (t !== card.value.text) cv.editPrompt(card.value.id, t); };
</script>

<template>
  <section v-if="card" class="card prompt" :class="{ focus: cv.focus === card.id }">
    <Handle type="target" :position="Position.Left" /><Handle type="source" :position="Position.Right" />
    <header class="drag-handle"><span class="plab">프롬프트</span>
      <div class="t"><small>{{ card.author }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="프롬프트 카드 닫기">✕</button></header>
    <textarea class="nodrag" :value="card.text" rows="4" :readonly="cv.readOnly" aria-label="프롬프트 내용"
      placeholder="결과물·질문을 만들 때 따를 지시를 적으세요 (예: 회수 기간은 보수적으로 잡을 것)" @blur="save" />
  </section>
</template>
