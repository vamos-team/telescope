<script setup lang="ts">
// 왼쪽 사이드바: 근거 데이터(캔버스에 올라간 자료) / 활동 로그. 캔버스 목록은 캔버스 홈(탭 줄 맨 왼쪽)에서
import { ref, computed } from "vue";
import { api, docTitle } from "../api";
import { useCanvas, memberOf, who, ROLE } from "../stores/canvas";

const emit = defineEmits<{ focus: [id: string] }>();
const cv = useCanvas();
const view = ref<"layers" | "log">("layers");  // 근거 데이터 / 활동 로그
const folded = ref(new Set<string>());  // 접은 데이터 유형
function fold(g: string) { const s = new Set(folded.value); s.has(g) ? s.delete(g) : s.add(g); folded.value = s; }
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
      <div class="lptabs tabs" role="tablist">
        <button role="tab" :aria-selected="view === 'layers'" @click="view = 'layers'">근거 데이터</button>
        <button role="tab" :aria-selected="view === 'log'" @click="view = 'log'">활동 로그</button>
      </div>
    </div>

    <!-- 활동 로그: 누르면 사이드바 안에서 옆으로 밀려 들어옴 -->
    <section v-if="view === 'log'" key="log" class="log-pane slidein">
      <div class="lh"><h2>이 캔버스 사람</h2><small>{{ cv.members.length }}</small></div>
      <ul class="mem"><li v-for="m in cv.members" :key="m.id"><span class="av" :data-me="m.name === cv.author">{{ m.name[0] }}</span>{{ m.name }}<small>{{ m.dept }} · {{ m.job }} · {{ ROLE[m.role] }}</small></li></ul>
      <div class="lh"><h2>활동 기록</h2><small>{{ cv.activity.length }}</small></div>
      <p v-if="!cv.activity.length" class="none">아직 기록이 없습니다.</p>
      <ul class="log"><li v-for="(a, i) in cv.activity.slice(0, 80)" :key="i"><b :title="who(a.author)">{{ a.author }}</b> {{ a.text }}<small>{{ a.time }}</small></li></ul>
      <p class="note">데모는 한 브라우저 안에서 다른 사번으로 다시 로그인해 보여 줍니다. 실제 서비스는 서버 이벤트 로그로 화면을 맞춥니다.</p>
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
