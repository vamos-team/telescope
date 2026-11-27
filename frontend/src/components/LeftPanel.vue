<script setup lang="ts">
// 캔버스 왼쪽 사이드바: 근거 데이터(캔버스에 올라간 자료) / 메모(카드 메모 · 메모 카드 · 선 메모). 헤더 아래 플로팅 버튼으로 열고 닫음
import { ref, computed } from "vue";
import { api, docTitle, KIND_LABEL } from "../api";
import { useCanvas, memberOf } from "../stores/canvas";

const emit = defineEmits<{ focus: [id: string]; link: [id: string]; close: [] }>();
const cv = useCanvas();
const view = ref<"layers" | "memos">("layers");
const folded = ref(new Set<string>());  // 접은 데이터 유형
function fold(g: string) { const s = new Set(folded.value); s.has(g) ? s.delete(g) : s.add(g); folded.value = s; }
// 데이터 성격(src): dart 공시 / ir·ar / news / internal / viz(비교·시각화·대시보드). 색은 style.css의 data-src
const SRC_GROUP: Record<string, string> = { ir: "IR·AR", ar: "IR·AR", news: "뉴스", internal: "사내 자료" };
const cardTitle = cv.cardTitle;
const myJob = computed(() => cv.user?.job ?? memberOf(cv.user!.name)?.job);  // 내 직무: 서버 로그인은 응답 값, local은 목업 구성원
const groups = computed(() => {
  const g: Record<string, { id: string; title: string; sub: string; co?: string; src: string }[]> = { "공시 표": [], "IR·AR": [], "뉴스": [], "사내 자료": [], "비교·시각화": [] };
  for (const c of cv.cards) {
    if (c.kind === "table") { const t = api.tableInfo(c.tableId); g["공시 표"].push({ id: c.id, title: t.title, sub: `${t.period || t.basis} · ${c.rows.length}행`, co: t.company, src: "dart" }); }
    else if (c.kind === "doc") { const d = api.docs.get(c.docId); if (d) g[SRC_GROUP[d.kind]].push({ id: c.id, title: docTitle(d), sub: `${KIND_LABEL[d.kind]} · ${d.publisher} · ${d.date}`, co: d.company, src: d.kind }); }
    else if (c.kind === "compare") g["비교·시각화"].push({ id: c.id, title: "계정 대응 비교", sub: `표 ${c.sources.length}개`, src: "viz" });
    else if (c.kind === "chart") g["비교·시각화"].push({ id: c.id, title: "시각화", sub: `표 ${c.sources.length}개`, src: "viz" });
    else if (c.kind === "dash") g["비교·시각화"].push({ id: c.id, title: cardTitle(c.id), sub: "대시보드", src: "viz" });
  }
  return Object.entries(g).filter(([, xs]) => xs.length);
});
// 메모 목록: 카드 메모, 메모 카드, 선 메모. 누르면 그 카드나 선으로 이동
const KIND = { card: "카드 메모", memo: "메모 카드", link: "연결 메모" } as const;
const notes = computed(() => cv.notes.map((n) => ({ ...n, label: KIND[n.kind] + (n.author ? ` · ${n.author}` : ""),
  go: () => (n.linkId ? emit("link", n.linkId) : emit("focus", n.cardId!)) })));
</script>

<template>
  <aside class="lp" aria-label="캔버스와 근거 데이터">
    <div class="lphead">
      <div class="lptabs tabs" role="tablist">
        <button role="tab" :aria-selected="view === 'layers'" @click="view = 'layers'">근거 데이터</button>
        <button role="tab" :aria-selected="view === 'memos'" @click="view = 'memos'">메모 <small>{{ notes.length }}</small></button>
      </div>
      <button class="pclose" data-tip="사이드바 접기" aria-label="왼쪽 사이드바 접기" @click="emit('close')"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M7.5 3.5v13" /></svg></button>
    </div>

    <section v-if="view === 'memos'" key="memos" class="plist slidein">
      <p v-if="!notes.length" class="none">아직 메모가 없습니다. 카드 오른쪽 위 +로 메모 카드를 달거나, 선을 눌러 메모를 입력하세요.</p>
      <!-- 메모 목록: 사람 · 내용 · 연결 (종류 표시 · 노란 박스 없음) -->
      <button v-for="n in notes" :key="n.key" class="mi" @click="n.go()">
        <span class="av" :data-me="n.mine">{{ n.author ? n.author[0] : "·" }}</span>
        <span class="mt"><b>{{ n.author ?? "작성자 없음" }}</b><span class="tx">{{ n.text }}</span><small>{{ n.where }}</small></span></button>
    </section>

    <section v-else key="layers" class="layers slidein">
      <div v-for="[g, xs] in groups" :key="g" class="lg">
        <!-- 데이터 유형별로 접고 펴기 -->
        <h3><button class="gt" :aria-expanded="!folded.has(g)" @click="fold(g)">
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 5l5 5-5 5" /></svg>{{ g }} <small>{{ xs.length }}</small></button></h3>
        <button v-for="x in folded.has(g) ? [] : xs" :key="x.id" class="ly" :class="{ on: cv.focus === x.id }" @click="emit('focus', x.id)">
          <span class="dot" :data-src="x.src" /><span class="tt"><b>{{ x.title }}</b><small>{{ x.sub }}</small></span>
          <span v-if="x.co" class="co" :data-src="x.src">{{ x.co }}</span>
        </button>
      </div>
    </section>

    <div class="who"><span class="av" data-me="true">{{ cv.user!.name[0] }}</span>
      <div><b>{{ cv.user!.name }}</b><small v-if="cv.user!.dept">{{ cv.user!.dept }}</small><small v-if="myJob">{{ myJob }}</small></div>
      <button class="lo" @click="cv.logout()">로그아웃</button></div>
  </aside>
</template>
