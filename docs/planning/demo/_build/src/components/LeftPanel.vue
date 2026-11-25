<script setup lang="ts">
// 왼쪽 사이드바: 근거 데이터(캔버스에 올라간 자료) / 활동 로그 / 캔버스 목록(맨 위 오른쪽 아이콘, 팝업 없이 사이드바 안에서)
import { ref, computed } from "vue";
import { api, docTitle } from "../api";
import { useCanvas, memberOf, who } from "../stores/canvas";

const emit = defineEmits<{ focus: [id: string] }>();
const cv = useCanvas();
const view = ref<"layers" | "log" | "pages">("layers");  // 근거 데이터 / 활동 로그 / 캔버스 목록
const back = ref<"layers" | "log">("layers");  // 캔버스 목록을 닫으면 돌아갈 보기
const folded = ref(new Set<string>());  // 접은 데이터 유형
function fold(g: string) { const s = new Set(folded.value); s.has(g) ? s.delete(g) : s.add(g); folded.value = s; }
function togglePages() { if (view.value === "pages") view.value = back.value; else { back.value = view.value; view.value = "pages"; } }
const editing = ref("");
const vFocus = { mounted: (el: HTMLInputElement) => el.select() };
const when = (iso: string) => {
  const d = new Date(iso), m = Math.round((Date.now() - d.getTime()) / 60000);
  return m < 1 ? "방금" : m < 60 ? `${m}분 전` : m < 1440 ? `${Math.round(m / 60)}시간 전` : d.toLocaleDateString("ko-KR");
};
function done(id: string, v: string) { if (id === cv.canvasId) cv.rename(v); editing.value = ""; }
function del(id: string, name: string) { if (confirm(`'${name}' 캔버스를 지울까요? 부서원 모두에게서 지워집니다.`)) cv.removeCanvas(id); }
const KIND = { ir: "IR", news: "뉴스", internal: "사내 자료" };
const groups = computed(() => {
  const g: Record<string, { id: string; title: string; sub: string; co?: string }[]> = { "공시 표": [], "IR·뉴스": [], "사내 자료": [], "비교·시각화": [], "메모": [] };
  for (const c of cv.cards) {
    if (c.kind === "table") { const t = api.tableInfo(c.tableId); g["공시 표"].push({ id: c.id, title: t.title, sub: `${t.period || t.basis} · ${c.rows.length}행`, co: t.company }); }
    else if (c.kind === "doc") { const d = api.docs.get(c.docId); if (d) g[d.kind === "internal" ? "사내 자료" : "IR·뉴스"].push({ id: c.id, title: docTitle(d), sub: `${KIND[d.kind]} · ${d.publisher} · ${d.date}`, co: d.company }); }
    else if (c.kind === "compare") g["비교·시각화"].push({ id: c.id, title: "계정 대응 비교", sub: `표 ${c.sources.length}개` });
    else if (c.kind === "chart") g["비교·시각화"].push({ id: c.id, title: "시각화", sub: `비교 ${c.compareId}` });
    else g["메모"].push({ id: c.id, title: c.text.slice(0, 30) || "빈 메모", sub: `${c.type === "request" ? "보완 요청" : "메모"} · ${c.author}` });
  }
  return Object.entries(g).filter(([, xs]) => xs.length);
});
</script>

<template>
  <aside class="lp" aria-label="캔버스와 근거 데이터">
    <div class="lphead">
      <button class="sbx" :aria-pressed="view === 'pages'" title="캔버스 목록" aria-label="캔버스 목록" @click="togglePages">
        <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 5h10M7 10h10M7 15h10M3.5 5h.01M3.5 10h.01M3.5 15h.01" /></svg></button>
      <div class="lptabs tabs" role="tablist">
        <button role="tab" :aria-selected="view === 'layers'" @click="view = 'layers'">근거 데이터</button>
        <button role="tab" :aria-selected="view === 'log'" @click="view = 'log'">활동 로그</button>
      </div>
    </div>

    <!-- 캔버스 목록: 맨 위 오른쪽 아이콘으로 열고 닫음 (팝업 없이 사이드바 안에서) -->
    <section v-if="view === 'pages'" key="pages" class="pages slidein">
      <div class="lh"><h2>{{ cv.user!.dept }} 캔버스</h2><small>{{ cv.canvases.length }}</small><button aria-label="새 캔버스" title="새 캔버스" @click="cv.newCanvas()">+</button></div>
      <ul>
        <li v-for="m in cv.canvases" :key="m.id" :aria-current="m.id === cv.canvasId">
          <input v-if="editing === m.id" :value="m.name" aria-label="캔버스 이름" v-focus
            @keydown.enter="done(m.id, ($event.target as HTMLInputElement).value)" @blur="done(m.id, ($event.target as HTMLInputElement).value)" />
          <button v-else class="pg" @click="cv.open(m.id)" @dblclick="m.id === cv.canvasId && (editing = m.id)" :title="m.id === cv.canvasId ? '두 번 눌러 이름 바꾸기' : ''">
            <b>{{ m.name }}</b><small>카드 {{ m.count }} · {{ m.updatedBy }} · {{ when(m.updated) }}</small></button>
          <button class="x" :aria-label="m.name + ' 지우기'" @click="del(m.id, m.name)">✕</button>
        </li>
      </ul>
    </section>

    <!-- 활동 로그: 누르면 사이드바 안에서 옆으로 밀려 들어옴 -->
    <section v-else-if="view === 'log'" key="log" class="log-pane slidein">
      <div class="lh"><h2>팀원</h2><small>{{ cv.members.length }}</small></div>
      <ul class="mem"><li v-for="m in cv.members" :key="m.name"><span class="av" :data-me="m.name === cv.author">{{ m.name[0] }}</span>{{ m.name }}<small>{{ m.dept }} · {{ m.job }}</small></li></ul>
      <div class="lh"><h2>활동 기록</h2><small>{{ cv.activity.length }}</small></div>
      <p v-if="!cv.activity.length" class="none">아직 기록이 없습니다.</p>
      <ul class="log"><li v-for="(a, i) in cv.activity.slice(0, 80)" :key="i"><b :title="who(a.author)">{{ a.author }}</b> {{ a.text }}<small>{{ a.time }}</small></li></ul>
      <p class="note">데모는 한 브라우저 안에서 작성자만 바꿔 보여 줍니다. 실제 서비스는 서버 이벤트 로그로 팀원 화면을 맞춥니다.</p>
    </section>


    <section v-else key="layers" class="layers slidein">
      <p v-if="!cv.cards.length" class="none">캔버스에 올린 자료가 여기에 쌓입니다.</p>
      <div v-for="[g, xs] in groups" :key="g" class="lg">
        <!-- 데이터 유형별로 접고 펴기 -->
        <h3><button class="gt" :aria-expanded="!folded.has(g)" @click="fold(g)">
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M7 5l5 5-5 5" /></svg>{{ g }} <small>{{ xs.length }}</small></button></h3>
        <button v-for="x in folded.has(g) ? [] : xs" :key="x.id" class="ly" :class="{ on: cv.focus === x.id }" @click="emit('focus', x.id)">
          <span class="tt"><b>{{ x.title }}</b><small>{{ x.sub }}</small></span>
          <span v-if="x.co" class="co" :data-co="x.co">{{ x.co }}</span>
        </button>
      </div>
    </section>

    <div class="who"><span class="av" data-me="true">{{ cv.user!.name[0] }}</span>
      <div><b>{{ cv.user!.name }}</b><small>{{ cv.user!.dept }} · {{ memberOf(cv.user!.name)?.job }}</small></div>
      <button class="lo" @click="cv.logout()">로그아웃</button></div>
  </aside>
</template>
