<script setup lang="ts">
// 카드 바깥 장식 (카드 위에 띄움): 검토 지적 라벨, 고른 카드의 + (메모 카드 달기)
import { computed } from "vue";
import { useVueFlow } from "@vue-flow/core";
import { useCanvas } from "../../stores/canvas";

const props = defineProps<{ id: string; memo?: boolean }>();
const emit = defineEmits<{ flag: [id: string] }>();
const cv = useCanvas();
const flow = useVueFlow("canvas");
const flags = computed(() => cv.flags[props.id] ?? []);
const plus = computed(() => !props.memo && cv.canEdit && cv.active === props.id && cv.selected.length <= 1);
function add() {
  const id = cv.addMemoFor(props.id, flow.findNode(props.id)?.dimensions.width ?? 420);
  setTimeout(() => (document.querySelector(`[data-id="${id}"] textarea`) as HTMLElement | null)?.focus(), 80);
}
</script>

<template>
  <div v-if="flags.length" class="flags nodrag">
    <button v-for="(f, i) in flags" :key="i" class="flag" :data-lv="f.level" title="분석 화면에서 보기" @click.stop="emit('flag', id)">{{ f.text }}</button>
  </div>
  <button v-if="plus" class="addmemo nodrag" data-tip="메모 카드 달기" aria-label="메모 카드 달기" @click.stop="add"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 3.5h12v9l-4 4H4z" /><path d="M12 16.5v-4h4" /></svg></button>
</template>
