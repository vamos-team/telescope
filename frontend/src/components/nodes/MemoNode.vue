<script setup lang="ts">
// 메모 카드 (F-07): 다른 카드에 선으로 이어 다는 메모. 종류 구분 없음 (지시 · 근거 · 의견은 분석에서 에이전트가 가림)
import { computed } from "vue";
import { useCanvas, memberOf, type MemoCard } from "../../stores/canvas";
import MemoEditor from "../MemoEditor.vue";
import CardChrome from "./CardChrome.vue";
import SideHandles from "./SideHandles.vue";

defineOptions({ inheritAttrs: false });  // 장식(CardChrome) + 카드 두 뿌리라 Vue Flow 속성은 넘기지 않음
const props = defineProps<{ data: { cardId: string; onFlag?: (id: string) => void } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as MemoCard);
const m = computed(() => memberOf(card.value.author));
/** 내가 이 화면에서 방금 만든 메모인지 */
const mine = computed(() => cv.myNewMemo === props.data.cardId);
</script>

<template>
  <CardChrome v-if="card" :id="card.id" memo @flag="(id) => data.onFlag?.(id)" />
  <section v-if="card" class="card memo" data-src="memo" :class="{ focus: cv.focus === card.id }">
    <SideHandles />
    <header class="drag-handle">
      <div class="t"><b>{{ card.author }}</b><small v-if="m">{{ m.dept }} · {{ m.job }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="메모 닫기">✕</button></header>
    <!-- 쓰는 중으로 열기 · 자동 포커스는 내가 방금 만든 메모만. 다른 사람이 만든 메모가 내 입력 포커스를 뺏지 않게 -->
    <MemoEditor :key="card.id" :text="card.text" :readonly="cv.readOnly" :autofocus="mine" :start-editing="mine" @save="(t) => cv.editMemo(card.id, t)" />
  </section>
</template>
