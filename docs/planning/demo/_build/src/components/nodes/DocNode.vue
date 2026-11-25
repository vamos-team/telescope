<script setup lang="ts">
// IR·뉴스·사내 자료 카드. 내용은 요약본과 핵심 수치(f번호 = 프롬프트의 [doc:ID#f번호])만, 원문은 출처 링크로
import { computed } from "vue";
import { Handle, Position } from "@vue-flow/core";
import { api, docTitle } from "../../api";
import { useCanvas, type DocCard } from "../../stores/canvas";

const props = defineProps<{ data: { cardId: string } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as DocCard);
const d = computed(() => api.docs.get(card.value.docId));
const KIND = { ir: "IR", news: "뉴스", internal: "사내 자료" };
</script>

<template>
  <section v-if="d" class="card doc" :data-kind="d.kind" :class="{ focus: cv.focus === card.id }">
    <Handle type="target" :position="Position.Left" /><Handle type="source" :position="Position.Right" />
    <header class="drag-handle"><span class="kind" :data-kind="d.kind">{{ KIND[d.kind] }}</span>
      <div class="t"><b :title="d.title">{{ docTitle(d) }}</b><small>{{ d.company }} / {{ d.publisher }} / {{ d.date }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <p class="sum">{{ d.summary }}</p>
    <table v-if="d.figures.length" class="g"><tbody>
      <tr v-for="(f, i) in d.figures" :key="i"><td>{{ f.label }}<small>{{ f.period }}</small></td>
        <td>{{ f.value.toLocaleString("ko-KR") }} {{ f.unit }}</td><td class="fid">f{{ i }}</td></tr>
    </tbody></table>
    <footer><span class="tags"><span v-for="t in d.tags" :key="t" class="tag">{{ api.analysis.tag_names[t] ?? t }}</span></span>
      <a v-if="d.url" class="nodrag" :href="d.url" target="_blank" rel="noopener">출처 열기</a><span v-else>사내 보관</span></footer>
  </section>
</template>
