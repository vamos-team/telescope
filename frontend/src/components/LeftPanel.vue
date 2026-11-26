<script setup lang="ts">
// 왼쪽 사이드바: 근거 데이터(캔버스에 올라간 자료·메모) / 프롬프트(카드·선 프롬프트 목록). 캔버스 목록은 로고 팝업에서
import { ref, computed } from "vue";
import { api, docTitle, KIND_LABEL } from "../api";
import { useCanvas, memberOf } from "../stores/canvas";

const emit = defineEmits<{ focus: [id: string]; link: [id: string] }>();
const cv = useCanvas();
const view = ref<"layers" | "prompts">("layers");
const folded = ref(new Set<string>());  // 접은 데이터 유형
function fold(g: string) { const s = new Set(folded.value); s.has(g) ? s.delete(g) : s.add(g); folded.value = s; }
// 데이터 성격(src): dart 공시 / ir·ar / news / internal / viz(비교·시각화·대시보드). 색은 style.css의 data-src
const SRC_GROUP: Record<string, string> = { ir: "IR·AR", ar: "IR·AR", news: "뉴스", internal: "사내 자료" };
const cardTitle = cv.cardTitle;
const groups = computed(() => {
  const g: Record<string, { id: string; title: string; sub: string; co?: string; src: string }[]> = { "공시 표": [], "IR·AR": [], "뉴스": [], "사내 자료": [], "비교·시각화": [], "메모": [] };
  for (const c of cv.cards) {
    if (c.kind === "table") { const t = api.tableInfo(c.tableId); g["공시 표"].push({ id: c.id, title: t.title, sub: `${t.period || t.basis} · ${c.rows.length}행`, co: t.company, src: "dart" }); }
    else if (c.kind === "doc") { const d = api.docs.get(c.docId); if (d) g[SRC_GROUP[d.kind]].push({ id: c.id, title: docTitle(d), sub: `${KIND_LABEL[d.kind]} · ${d.publisher} · ${d.date}`, co: d.company, src: d.kind }); }
    else if (c.kind === "compare") g["비교·시각화"].push({ id: c.id, title: "계정 대응 비교", sub: `표 ${c.sources.length}개`, src: "viz" });
    else if (c.kind === "chart") g["비교·시각화"].push({ id: c.id, title: "시각화", sub: `비교 ${c.compareId}`, src: "viz" });
    else if (c.kind === "dash") g["비교·시각화"].push({ id: c.id, title: cardTitle(c.id), sub: "대시보드", src: "viz" });
    else if (c.kind === "memo") g["메모"].push({ id: c.id, title: c.text.slice(0, 30) || "빈 메모", sub: `${c.type === "request" ? "보완 요청" : "메모"} · ${c.author}`, src: "memo" });
  }
  return Object.entries(g).filter(([, xs]) => xs.length);
});
// 프롬프트 목록: 카드 프롬프트, 선 프롬프트. 누르면 그 카드나 선으로 이동
const prompts = computed(() => [
  ...cv.cards.flatMap((c) => c.kind !== "memo" && c.prompt ? [{ key: "p" + c.id, kind: "카드", where: cardTitle(c.id), text: c.prompt, go: () => emit("focus", c.id) }] : []),
  ...cv.links.flatMap((l) => l.prompt ? [{ key: l.id, kind: "선 · " + l.rel, where: `${cardTitle(l.source)} → ${cardTitle(l.target)}`, text: l.prompt, go: () => emit("link", l.id) }] : []),
]);
</script>

<template>
  <aside class="lp" aria-label="캔버스와 근거 데이터">
    <div class="lphead">
      <div class="lptabs tabs" role="tablist">
        <button role="tab" :aria-selected="view === 'layers'" @click="view = 'layers'">근거 데이터</button>
        <button role="tab" :aria-selected="view === 'prompts'" @click="view = 'prompts'">프롬프트 <small>{{ prompts.length }}</small></button>
      </div>
    </div>

    <section v-if="view === 'prompts'" key="prompts" class="plist slidein">
      <p v-if="!prompts.length" class="none">아직 프롬프트가 없습니다. 카드나 선을 눌러 프롬프트를 적으세요.</p>
      <button v-for="p in prompts" :key="p.key" class="pi" @click="p.go()">
        <span class="ptag">{{ p.kind }}</span><b>{{ p.text }}</b><small>{{ p.where }}</small></button>
    </section>

    <section v-else key="layers" class="layers slidein">
      <div v-for="[g, xs] in groups" :key="g" class="lg">
        <!-- 데이터 유형별로 접고 펴기 -->
        <h3><button class="gt" :aria-expanded="!folded.has(g)" @click="fold(g)">
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 5l5 5-5 5" /></svg>{{ g }} <small>{{ xs.length }}</small></button></h3>
        <button v-for="x in folded.has(g) ? [] : xs" :key="x.id" class="ly" :class="{ on: cv.focus === x.id }" @click="emit('focus', x.id)">
          <span class="tt"><b>{{ x.title }}</b><small>{{ x.sub }}</small></span>
          <span v-if="x.co" class="co" :data-src="x.src">{{ x.co }}</span>
        </button>
      </div>
    </section>

    <div class="who"><span class="av" data-me="true">{{ cv.user!.name[0] }}</span>
      <div><b>{{ cv.user!.name }}</b><small>{{ cv.user!.dept }} · {{ memberOf(cv.user!.name)?.job }}</small></div>
      <button class="lo" @click="cv.logout()">로그아웃</button></div>
  </aside>
</template>
