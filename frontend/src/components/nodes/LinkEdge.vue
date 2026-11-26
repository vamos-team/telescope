<script setup lang="ts">
// 사용자 연결선: 메모가 없으면 점선, 메모가 있으면 진한 실선 + 가운데에 메모 단 사람 아바타(피그마 댓글처럼)
// 아바타에 마우스를 올리면 작성자와 메모, 누르면 연결 바가 열림
import { computed } from "vue";
import { BaseEdge, EdgeLabelRenderer, getBezierPath, type EdgeProps } from "@vue-flow/core";
import { memberOf } from "../../stores/canvas";

const props = defineProps<EdgeProps<{ memo?: string; by?: string; onOpen?: (id: string) => void }>>();
const path = computed(() => getBezierPath(props));
const who = computed(() => props.data?.by ?? "");
const m = computed(() => memberOf(who.value));
</script>

<template>
  <BaseEdge :id="id" :path="path[0]" :marker-end="markerEnd" :style="style" :interaction-width="20" />
  <EdgeLabelRenderer v-if="data?.memo">
    <button class="eav nodrag nopan" :style="{ transform: `translate(-50%, -50%) translate(${path[1]}px, ${path[2]}px) scale(var(--iz, 1))` }"
      :data-tip="`${who}${m ? ' · ' + m.dept : ''}: ${data.memo}`" :aria-label="`${who} 메모: ${data.memo}`" @click.stop="data.onOpen?.(id)">
      {{ who ? who[0] : "?" }}</button>
  </EdgeLabelRenderer>
</template>
