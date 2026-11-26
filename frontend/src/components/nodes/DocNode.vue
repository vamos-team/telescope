<script setup lang="ts">
// IR·AR·뉴스·사내 자료 카드. 내용은 요약본과 핵심 수치(f번호 = 프롬프트의 [doc:ID#f번호])만, 원문은 출처 링크로
import { computed } from "vue";
import { api, docTitle, KIND_LABEL } from "../../api";
import { useCanvas, type DocCard } from "../../stores/canvas";
import CardMemo from "./CardMemo.vue";
import CardChrome from "./CardChrome.vue";
import SideHandles from "./SideHandles.vue";

defineOptions({ inheritAttrs: false });  // 장식(CardChrome) + 카드 두 뿌리라 Vue Flow 속성은 넘기지 않음
const props = defineProps<{ data: { cardId: string; onFlag?: (id: string) => void } }>();
const cv = useCanvas();
const card = computed(() => cv.cards.find((c) => c.id === props.data.cardId) as DocCard);
const d = computed(() => api.docs.get(card.value.docId));
</script>

<template>
  <CardChrome v-if="card" :id="card.id" @flag="(id) => data.onFlag?.(id)" />
  <section v-if="d" class="card doc" :data-kind="d.kind" :class="{ focus: cv.focus === card.id }">
    <SideHandles />
    <header class="drag-handle"><span class="kind" :data-kind="d.kind">{{ KIND_LABEL[d.kind] }}</span>
      <div class="t"><b :title="d.title">{{ docTitle(d) }}</b><small>{{ d.company }} / {{ d.publisher }} / {{ d.date }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
    <div v-if="d.img" class="nimg"><img :src="d.img" alt="" loading="lazy" referrerpolicy="no-referrer" draggable="false" @error="($event.target as HTMLElement).remove()"><span>{{ d.publisher.slice(0, 1) }}</span></div>
    <p v-if="d.summary !== d.title" class="sum">{{ d.summary }}</p>
    <table v-if="d.figures.length" class="g"><tbody>
      <tr v-for="(f, i) in d.figures" :key="i"><td>{{ f.label }}<small>{{ f.period }}</small></td>
        <td>{{ f.value.toLocaleString("ko-KR") }} {{ f.unit }}</td><td class="fid">f{{ i }}</td></tr>
    </tbody></table>
    <footer><span class="tags"><span v-for="t in d.tags" :key="t" class="tag">{{ api.analysis.tag_names[t] ?? t }}</span></span>
      <a v-if="d.url" class="nodrag" :href="d.url" target="_blank" rel="noopener">출처 열기</a><span v-else>사내 보관</span></footer>
    <CardMemo :id="card.id" :text="card.memo" />
  </section>
  <!-- 문서를 찾을 수 없을 때(예: 지난 데이터의 자료)도 카드를 숨기지 않고 상태를 보여 줌 -->
  <section v-else-if="card" class="card doc missing" :class="{ focus: cv.focus === card.id }">
    <SideHandles />
    <header class="drag-handle"><span class="kind">자료 없음</span>
      <div class="t"><b>자료를 찾을 수 없습니다</b><small>{{ card.docId }}</small></div>
      <button class="nodrag" @click="cv.remove(card.id)" aria-label="카드 닫기">✕</button></header>
  </section>
</template>
