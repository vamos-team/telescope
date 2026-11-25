<script setup lang="ts">
// 팀원 메모 카드 (F-07): 일반 메모 / 보완 요청. 컨텍스트에는 "의견"으로 들어가고 사실 근거로는 쓰지 않는다
import { computed } from "vue";
import { Handle, Position } from "@vue-flow/core";
import { useCanvas, memberOf, type MemoCard } from "../../stores/canvas";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as MemoCard);
const m = computed(() => memberOf(card.value.author));
const save = (e: Event) => { const t = (e.target as HTMLTextAreaElement).value.trim(); if (t !== card.value.text) cv.editMemo(card.value.id, t); };
</script>

<template>
  <section v-if="card" class="card memo" :class="{ focus: cv.focus === card.id, req: card.type === 'request' }">
    <Handle type="target" :position="Position.Left" /><Handle type="source" :position="Position.Right" />
    <header class="drag-handle">
      <div class="seg mt nodrag" role="group" aria-label="메모 종류">
        <button :aria-pressed="card.type !== 'request'" @click="cv.setMemoType(card.id, 'memo')">메모</button>
        <button :aria-pressed="card.type === 'request'" @click="cv.setMemoType(card.id, 'request')">보완 요청</button>
      </div>
      <div class="t"><b>{{ card.author }}</b><small v-if="m">{{ m.dept }} · {{ m.job }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="메모 닫기">✕</button></header>
    <textarea class="nodrag" :value="card.text" rows="3" aria-label="메모 내용" :placeholder="card.type === 'request' ? '보완이 필요한 근거나 수치를 적으세요' : '팀원에게 남길 메모'" @blur="save" />
  </section>
</template>
