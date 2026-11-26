<script setup lang="ts">
// 분석 화면 (페이지의 두 번째 화면). 캔버스 하단 "분석하기"를 누르면 이 화면으로 바뀌고 분석을 시작
// 분석 = 검토(검토 내역) → 질문(애널리스트 질문) → 평가(예측 평가, 다음 분기 자료가 들어온 뒤). v01-stella 보고서 모양의 문서 (목차 없음)
// 분석마다 세션이 하나씩 쌓이고(고치지 않음), 왼쪽 목록에서 지난 세션을 다시 봄. 세션은 요청한 본인만 봄
import { ref, computed, watch, nextTick } from "vue";
import { api, docTitle } from "../api";
import { review as makeReview, questions as makeQuestions, assumptions as makeAssumptions, evaluate, reportMd, topicName, claimPrompt, analysisMd,
  type Session, type Review, type Question } from "../local/ai";
import { useCanvas, quarterOf } from "../stores/canvas";

const props = defineProps<{ run: number; flag: { id: string; n: number } | null }>();  // run: 분석하기 누른 횟수, flag: 캔버스 지적 라벨에서 넘어옴
const emit = defineEmits<{ cite: [c: string]; card: [id: string]; analyze: [] }>();
const cv = useCanvas();
const A = api.analysis;
type Step = "review" | "questions" | "eval";
const part = ref<Step>("review"), alOpen = ref(true), exportOpen = ref(false);
const doing = ref<Step | null>(null), live = ref<{ review: Review | null; questions: Question[] | null }>({ review: null, questions: null });
const curId = ref<string | null>(cv.analyses[0]?.id ?? null);
const cur = computed<Session | null>(() => cv.analyses.find((s) => s.id === curId.value) ?? null);
const rv = computed(() => doing.value ? live.value.review : cur.value?.review ?? null);
const qs = computed(() => doing.value ? live.value.questions : cur.value?.questions ?? null);
const ev = computed(() => cur.value?.eval ?? null);
// 세션 = 처음 분석과 그 업데이트 버전들(base로 묶음). 결과는 고치지 않고 버전으로 쌓는다. 목록은 최근에 바뀐 세션이 위
const lineOf = (s: Session) => s.base ?? s.id;
const lines = computed(() => { const m = new Map<string, Session[]>();
  for (const s of cv.analyses) m.set(lineOf(s), [s, ...(m.get(lineOf(s)) ?? [])]);  // analyses는 최신이 앞 → 버전은 오래된 것부터
  return [...m].map(([key, vers]) => ({ key, vers })); });
const curKey = computed(() => cur.value ? lineOf(cur.value) : null);
// 보고 있는 세션을 만든 뒤 캔버스가 바뀌었는지. 바뀌면 업데이트(이 세션을 다시 분석) · 새로 분석하기(새 세션) 중 고름
const stale = computed(() => !doing.value && !!cur.value && cur.value.sig !== cv.sig);

// 프로세스 바: 검토 → 질문이 한 줄로 끝까지 채워짐. 평가는 다음 분기 자료가 들어와야 하는 별도 단계(점선 뒤)라 바가 중간에 멈추지 않음
const st = (k: Step) => k === "eval" ? (ev.value && !doing.value ? "done" : "wait")
  : doing.value === k ? "doing" : doing.value === "questions" && k === "review" ? "done" : doing.value ? "wait" : cur.value ? "done" : "wait";
const fill = computed(() => st("questions") === "done" ? 100 : st("questions") === "doing" ? 50 : 0);  // 검토 → 질문 구간

const tick = () => new Promise((ok) => setTimeout(ok, 600));  // ponytail: 목업 AI는 즉시 끝나 진행이 안 보여 단계마다 짧게 쉼. LLM 연결 뒤 삭제
// mode new: 새 세션. update: 보고 있는 세션의 새 버전(이전 버전과 그 평가는 그대로 남음, 새 버전은 평가 대기)
async function start(mode: "new" | "update" = "new") {
  if (!cv.cards.length || doing.value) return;
  const target = mode === "update" ? cur.value : null;
  await cv.flush();
  const sid = cv.sessionId, sig = cv.sig;
  live.value = { review: null, questions: null }; doing.value = "review"; part.value = "review";
  await tick();
  const evs = await api.evidence(sid);
  const r = await makeReview(A, { canvas: cv.canvasName, quarter: quarterOf(cv.meta), cards: cv.cards, links: cv.links, memos: cv.memos, notes: cv.notes,
    sessionId: sid, title: cv.cardTitle, ev: evs, tags: cv.tags });
  if (sid !== cv.sessionId) { doing.value = null; return; }
  live.value.review = r; doing.value = "questions";
  await tick();
  const q = makeQuestions(A, evs);
  live.value.questions = q;
  const s: Session = { id: "a" + Date.now().toString(36), base: target ? lineOf(target) : undefined, at: new Date().toISOString(), sig, cards: cv.cards.length, quarter: quarterOf(cv.meta),
    review: r, questions: q, assume: makeAssumptions(A, evs), eval: null };
  cv.pushAnalysis(s);
  curId.value = s.id; doing.value = null;
}
// 캔버스의 분석하기: 세션이 없을 때만 새로 시작. 있으면 분석 화면만 열고, 캔버스가 바뀌었으면 위 막대에서 업데이트 · 새로 분석하기를 고름
watch(() => props.run, (n, o) => { if (n && n !== o && !cv.analyses.length) start("new"); }, { immediate: true });
watch(() => cv.analyses, (xs) => { if (!xs.some((s) => s.id === curId.value)) curId.value = xs[0]?.id ?? null; });
// 캔버스 지적 라벨을 누르고 넘어오면 검토의 그 카드 항목으로
watch(() => props.flag?.n, async () => {
  if (!props.flag) return;
  part.value = "review"; curId.value = cv.analyses[0]?.id ?? null;
  await nextTick();
  const el = document.querySelector(`.aview [data-card="${props.flag.id}"]`) as HTMLElement | null;
  el?.scrollIntoView({ block: "center", behavior: "smooth" });  // 이동만, 강조 표시 없음
}, { immediate: true });
// 지우기 = 내 목록에서 숨김 (결과는 저장소에 남음)
function delSession(key: string) {
  if (!confirm("이 분석을 목록에서 지울까요?")) return;
  const was = curKey.value === key;
  cv.hideAnalysis(key);
  if (was) { curId.value = cv.analyses[0]?.id ?? null; part.value = "review"; }
}
function applyNext() { if (cur.value) { cv.attachEval(cur.value.id, evaluate(A, cur.value.questions, cur.value.assume)); part.value = "eval"; } }

// ---------- 보기 도우미 ----------
const when = (iso: string) => new Date(iso).toLocaleString("ko-KR", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" });
const day = (iso?: string) => (iso ? new Date(iso) : new Date()).toLocaleDateString("sv-SE");
const VCLS: Record<string, string> = { 충분: "ok", 보통: "mid", 부족: "bad" };
const cnt = (v: string) => rv.value?.claims.filter((c) => c.verdict === v).length ?? 0;
// 총평 표: 주장 출처별(메모 · IR · 뉴스 …) 주장 수와 판정
const bySource = computed(() => { const m = new Map<string, number[]>();
  for (const c of rv.value?.claims ?? []) { const r = m.get(c.source) ?? [0, 0, 0, 0]; r[0]++; r[c.verdict === "충분" ? 1 : c.verdict === "보통" ? 2 : 3]++; m.set(c.source, r); }
  return [...m]; });
const findingsByCard = computed(() => {
  const m = new Map<string, { id: string; title: string; items: Review["findings"] }>();
  for (const f of rv.value?.findings ?? []) { if (!m.has(f.cardId)) m.set(f.cardId, { id: f.cardId, title: cv.cards.some((c) => c.id === f.cardId) ? cv.cardTitle(f.cardId) : "지운 카드", items: [] }); m.get(f.cardId)!.items.push(f); }
  return [...m.values()];
});
const needs = computed(() => [...new Set((rv.value?.claims ?? []).filter((c) => c.need && c.verdict !== "충분").map((c) => c.need!))]);
const newsOf = (id: string) => api.docs.get(id);
// 예상 질문: 쟁점(개념)별로 묶음
const qGroups = computed(() => { const m = new Map<string, Question[]>(); for (const q of qs.value ?? []) { const k = topicName(A, q); m.set(k, [...(m.get(k) ?? []), q]); } return [...m]; });
const qNo = (q: Question) => "Q" + ((qs.value ?? []).indexOf(q) + 1);
const hiQs = computed(() => (qs.value ?? []).filter((q) => q.level === "높음"));
// ---------- 내보내기 팝업: 캔버스 정리 글을 프롬프트로 복사 / Markdown 파일 ----------
const fileName = (what: string) => `${cv.canvasName}_${what}_${day().replace(/-/g, "")}.md`.replace(/[\\/:*?"<>|]/g, "_");
function download(name: string, text: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
  a.download = name; a.click(); URL.revokeObjectURL(a.href);
}
// 위 전환: 프롬프트로 추출(주장 · 근거 컨텍스트를 LLM에 물을 프롬프트, 복사) / Markdown으로 내보내기(검토 → 질문 → 평가 한 파일)
const xmode = ref<"prompt" | "md">("prompt"), copied = ref(false);
const xtext = computed(() => !rv.value ? "" : xmode.value === "prompt"
  ? claimPrompt(A, rv.value, cv.canvasName, cur.value?.quarter ?? quarterOf(cv.meta))
  : cur.value ? analysisMd(A, cur.value, cv.canvasName) : "");
async function copyX() { try { await navigator.clipboard.writeText(xtext.value); copied.value = true; setTimeout(() => (copied.value = false), 1500); } catch { /* 권한 없음 */ } }
</script>

<template>
  <div class="aview">
    <!-- 왼쪽: 분석 세션 목록 (접기 버튼은 안에) -->
    <aside v-if="alOpen" class="alist" aria-label="분석 세션">
      <div class="alh"><b>분석 세션 <small>{{ lines.length }}</small></b>
        <button class="pclose" data-tip="세션 목록 접기" aria-label="세션 목록 접기" @click="alOpen = false"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M7.5 3.5v13" /></svg></button></div>
      <p v-if="!cv.analyses.length" class="none">아직 분석하지 않았습니다.</p>
      <div v-for="(ln, i) in lines" :key="ln.key" class="arow">
      <button class="ai" :aria-current="ln.key === curKey && !doing ? 'true' : undefined" @click="curId = ln.vers[ln.vers.length - 1].id; part = 'review'">
        <b>{{ i === 0 ? "최신 분석" : `분석 ${lines.length - i}` }}<span v-if="ln.vers.length > 1" class="ver">v{{ ln.vers.length }}</span><span v-if="ln.vers[ln.vers.length - 1].sig !== cv.sig" class="old">이전 캔버스</span></b>
        <small>{{ when(ln.vers[ln.vers.length - 1].at) }}</small>
        <span class="st" :data-v="ln.vers[ln.vers.length - 1].review.overall.verdict">근거 {{ ln.vers[ln.vers.length - 1].review.overall.verdict }}</span><span class="st" :data-v="ln.vers[ln.vers.length - 1].eval ? 'done' : 'wait'">{{ ln.vers[ln.vers.length - 1].eval ? "평가 완료" : "평가 대기" }}</span>
      </button>
      <div v-if="ln.vers.length > 1 && ln.key === curKey" class="vers" role="tablist" aria-label="버전">
        <button v-for="(v, k) in ln.vers" :key="v.id" role="tab" :aria-selected="v.id === curId" :title="when(v.at)" @click="curId = v.id; part = 'review'">v{{ k + 1 }}</button></div>
      <button class="adel" data-tip="분석 지우기" aria-label="분석 지우기" :disabled="!!doing" @click="delSession(ln.key)"><svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 6h12M8 6V4.5h4V6M6 6l.7 9.5h6.6L14 6" /></svg></button>
      </div>
      <p class="own">분석 결과는 요청한 본인만 봅니다.</p>
    </aside>
    <button v-else class="float fl" data-tip="세션 목록 보기" aria-label="세션 목록 보기" @click="alOpen = true">
      <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M7.5 3.5v13" /></svg></button>

    <main class="abody">
      <div v-if="stale" class="stalebar" role="status">캔버스가 변경되었습니다.<button data-tip="이 세션을 지금 캔버스로 다시 분석" @click="start('update')">업데이트</button><button data-tip="새 세션으로 분석" @click="start('new')">새로 분석하기</button></div>

      <div v-if="!cur && !doing" class="aempty">
        <h2>캔버스를 분석합니다</h2>
        <p>AI가 캔버스의 근거를 검토하고(주장 · 근거 충분/부족 · 인사이트), 애널리스트가 던질 질문을 예측합니다.<br>평가는 다음 분기 자료가 올라오면 진행합니다.</p>
        <button class="big" :disabled="!cv.cards.length" @click="emit('analyze')">분석하기</button>
        <small v-if="!cv.cards.length">캔버스에 근거 카드를 먼저 올리세요.</small>
      </div>

      <template v-else>
        <!-- 위: 프로세스 바(검토 ─ 질문 ┄ 평가) + 내보내기 -->
        <div class="atop">
          <!-- 검토 ─ 질문(실선, 진행만큼 채움) ┄ 평가(다음 분기 자료 전까지 점선, 평가 뒤 실선) -->
          <div class="steps" role="tablist" aria-label="분석 단계">
            <span class="seg1" aria-hidden="true"><i :style="{ width: fill + '%' }" /></span>
            <span class="seg2" :class="{ on: !!ev && !doing }" aria-hidden="true" />
            <button role="tab" :aria-selected="part === 'review'" :data-st="st('review')" :disabled="!rv" @click="part = 'review'">
              <span class="dot">{{ st("review") === "done" ? "✓" : 1 }}</span><span class="nm">검토</span><small v-if="st('review') === 'doing'">진행 중</small></button>
            <button role="tab" :aria-selected="part === 'questions'" :data-st="st('questions')" :disabled="!qs" @click="part = 'questions'">
              <span class="dot">{{ st("questions") === "done" ? "✓" : 2 }}</span><span class="nm">질문</span><small v-if="st('questions') === 'doing'">진행 중</small></button>
            <button class="evstep" role="tab" :aria-selected="part === 'eval'" :data-st="st('eval')" :disabled="!cur || !!doing" @click="part = 'eval'">
              <span class="dot">{{ st("eval") === "done" ? "✓" : 3 }}</span><span class="nm">평가</span></button>
          </div>
          <button class="exp" :disabled="!rv" @click="exportOpen = true">
            <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 3v10M6 9l4 4 4-4M4 16h12" /></svg>내보내기</button>
        </div>

        <div class="rep">

          <!-- 검토 -->
          <article v-if="part === 'review'" class="doc">
            <div class="dtop"><div class="meta top"><h1>검토 내역</h1><span class="when">기준 시점 {{ cur?.quarter ?? quarterOf(cv.meta) }} · 작성 일자 {{ day(cur?.at) }}</span></div></div>
            <p v-if="!rv" class="loading">캔버스의 카드 · 연결 · 메모를 읽는 중…</p>
            <template v-else>
              <h2 id="r1">1. 총평</h2>
              <p>{{ rv.overall.text }}<template v-if="findingsByCard.length"> 캔버스 카드 {{ findingsByCard.length }}장에 고칠 점을 표시했어요.</template></p>
              <div class="tbl"><table><thead><tr><th>주장 출처</th><th class="n">주장</th><th class="n">근거 충분</th><th class="n">보통</th><th class="n">근거 부족</th></tr></thead>
                <tbody><tr v-for="[k, r] in bySource" :key="k"><td>{{ k }}</td><td class="n">{{ r[0] }}</td><td class="n">{{ r[1] }}</td><td class="n">{{ r[2] }}</td><td class="n" :class="{ 'st-bad': r[3] }">{{ r[3] }}</td></tr>
                  <tr class="sum"><td>합계</td><td class="n">{{ rv.claims.length }}</td><td class="n">{{ cnt("충분") }}</td><td class="n">{{ cnt("보통") }}</td><td class="n" :class="{ 'st-bad': cnt('부족') }">{{ cnt("부족") }}</td></tr></tbody></table></div>

              <h2 id="r2">2. 근거 검토</h2>
              <!-- 지적이 있으면 먼저 표로 -->
              <div v-if="rv.findings.length" class="tbl"><table><thead><tr><th>카드</th><th>지적</th><th /></tr></thead>
                <tbody><tr v-for="g in findingsByCard" :key="g.id" :data-card="g.id"><td>{{ g.title }}</td><td><span v-for="(f, i) in g.items" :key="i" class="tag bad">{{ f.text }}</span></td>
                  <td class="n"><button class="link" @click="emit('card', g.id)">캔버스에서 보기</button></td></tr></tbody></table></div>
              <p v-if="!rv.claims.length" class="none">검토할 주장이 없어요. 의견 메모나 IR · 뉴스 카드를 올리면 주장으로 검토해요.</p>
              <section v-for="c in rv.claims" :key="c.id" class="cbox" :data-card="c.cardId">
                <header><b>{{ c.from }}</b><span class="tag" :class="VCLS[c.verdict]">근거 {{ c.verdict }}</span>
                  <span v-for="f in (rv.findings.filter((x) => x.cardId === c.cardId && x.text !== '근거 부족'))" :key="f.text" class="tag bad">{{ f.text }}</span>
                  <button v-if="c.cardId" class="srcbox" @click="emit('card', c.cardId)">캔버스에서 보기</button></header>
                <p class="ctext">{{ c.text }}</p>
                <div class="blk"><div class="lab">근거별 평가</div>
                  <div v-if="c.evidence.length" class="evl">
                    <div v-for="e in c.evidence" :key="e.cite"><span class="el"><b>{{ e.label }}</b><small :class="{ okf: e.ok }">{{ e.fit ?? "" }}</small></span>
                      <span><span class="tag">{{ e.source }}</span><button class="cite" @click="emit('cite', e.cite)">{{ e.cite }}</button></span></div></div>
                  <p v-else class="none">캔버스에 이 주장을 받칠 근거가 없어요</p></div>
                <div v-if="c.need" class="aip"><div class="ai-lab">{{ c.verdict === "부족" ? "부족한 근거" : "보완하면 좋을 근거" }} · AI 판단</div><p>{{ c.need }}</p></div>
              </section>

              <h2 id="r3">3. AI 인사이트</h2>
              <p v-if="!rv.insights.length" class="none">캔버스 근거와 맞는 인사이트가 없어요.</p>
              <section v-for="i in rv.insights" :key="i.id" class="ins">
                <h3>{{ i.title }}</h3>
                <div class="blk"><div class="lab">사실 <span class="tag">공시</span><span class="tag">계산</span></div>
                  <ul class="facts"><li v-for="(f, k) in i.facts" :key="k">{{ f.text }} <button v-if="f.cite" class="cite" @click="emit('cite', f.cite)">{{ f.cite }}</button></li></ul></div>
                <div class="aip"><div class="ai-lab">AI 해석</div><p>{{ i.ai }}</p>
                  <div v-if="i.news.length" class="srcs"><template v-for="n in i.news" :key="n"><a v-if="newsOf(n)" :href="newsOf(n)!.url || undefined" target="_blank" rel="noopener">{{ newsOf(n)!.publisher }} · {{ docTitle(newsOf(n)!) }}</a></template></div></div>
              </section>

              <h2 id="r4">4. 보완하면 좋을 근거</h2>
              <ol v-if="needs.length" class="fix"><li v-for="n in needs" :key="n">{{ n }}</li></ol>
              <p v-else class="none">모든 주장에 근거가 충분해요.</p>
            </template>
          </article>

          <!-- 예상 질문 보고서 (v01 모양) -->
          <article v-else-if="part === 'questions'" class="doc">
            <div class="dtop"><div class="meta top"><h1>애널리스트 질문</h1>
              <span class="when">기준 시점 {{ cur?.quarter ?? quarterOf(cv.meta) }} · 작성 일자 {{ day(cur?.at) }}</span>
              <span class="btns"><button v-if="cur" class="btn line" @click="download(fileName('애널리스트질문'), reportMd(A, cur, cv.canvasName))">Markdown</button>
                <button v-if="cur" class="btn fill" @click="part = 'eval'">평가하기</button></span></div></div>
            <p v-if="!qs" class="loading">애널리스트 관점으로 질문을 만드는 중…</p>
            <template v-else>
              <h2 id="p1">1. 총평</h2>
              <p v-if="!qs.length">질문을 만들 근거가 없어요. 공시 표나 IR 자료를 캔버스에 올리세요.</p>
              <p v-else>예상 질문 {{ qs.length }}개를 뽑았고 {{ hiQs.length }}개는 중요도가 높아요. 중요도가 높은 질문은 {{ [...new Set(hiQs.map((q) => topicName(A, q)))].join(", ") || "없어요" }}에 대한 것이에요. 캔버스에 금액은 있지만 이유와 앞으로의 계획이 빠진 곳에서 나온 질문이에요.</p>
              <div v-if="qs.length" class="tbl"><table><thead><tr><th>쟁점</th><th class="n">질문</th><th class="n">중요도 높음</th><th class="n">보통</th></tr></thead>
                <tbody><tr v-for="[k, g] in qGroups" :key="k"><td>{{ k }}</td><td class="n">{{ g.length }}</td>
                  <td class="n" :class="{ 'st-bad': g.some((q) => q.level === '높음') }">{{ g.filter((q) => q.level === "높음").length }}</td><td class="n">{{ g.filter((q) => q.level !== "높음").length }}</td></tr></tbody></table></div>
              <template v-for="([k, g], i) in qGroups" :key="k">
                <h2 :id="`p${i + 2}`">{{ i + 2 }}. {{ k }}</h2>
                <div v-for="q in g" :key="q.topic" class="qa">
                  <div class="no"><b>{{ qNo(q) }}</b><span class="tag" :class="{ bad: q.level === '높음' }">중요도 {{ q.level }}</span></div>
                  <div class="qt">{{ q.q }}</div>
                  <div class="blk"><div class="lab">왜 묻나요</div><p>{{ q.why }}</p></div>
                  <div class="blk"><div class="lab">미리 준비할 근거</div>
                    <p v-if="q.cite.length"><button v-for="c in q.cite" :key="c" class="cite" @click="emit('cite', c)">{{ c }}</button></p><p v-else class="none">캔버스에 없어요</p></div>
                </div>
              </template>
            </template>
          </article>

          <!-- 예측 평가 -->
          <article v-else-if="cur" class="doc">
            <div class="dtop"><h1>예측 평가</h1><div class="meta"><span>예상 질문 {{ cur.questions.length }}개, 기준 시점 {{ cur.quarter }} 이전 자료로 예측 / 비교 대상 {{ A.next_quarter.label }} 실적 · 리포트 ({{ A.next_quarter.date }} 이후)</span></div></div>
            <div v-if="!ev" class="ewait">
              <b>다음 분기 자료를 기다리는 중이에요</b>
              <p>{{ A.next_quarter.label }} 실적과 애널리스트 리포트가 올라오면 이 세션의 질문 {{ cur.questions.length }}개와 가정 {{ cur.assume.length }}개를 대조해요. 기준 시점 이후 자료는 평가에만 써요.</p>
              <button class="btn fill" @click="applyNext">다음 분기 자료 반영 (데모)</button>
            </div>
            <template v-else>
              <h2 id="e1">1. 총평</h2>
              <p>예상 질문 {{ cur.questions.length }}개 중 {{ ev.hit.length }}개를 맞혔고 {{ ev.off.length }}개는 리포트에서 다루지 않았어요. 반대로 리포트 쟁점 {{ ev.hit.length + ev.missed.length }}개 중 {{ ev.missed.length }}개는 예측하지 못했어요. 정밀도는 {{ ev.precision }}%, 재현율은 {{ ev.recall }}%예요.</p>
              <h2 id="e2">2. 질문별 판정</h2>
              <div class="tbl"><table><thead><tr><th>예상 질문</th><th>대응한 리포트 쟁점</th><th>판정</th></tr></thead>
                <tbody><tr v-for="h in ev.hit" :key="h.q.topic"><td>{{ h.q.q }}</td><td><a :href="h.issue.url" target="_blank" rel="noopener">{{ h.issue.title }}</a> <span class="cap">{{ h.issue.publisher }}</span></td><td class="v-hit">맞힘</td></tr>
                  <tr v-for="q in ev.off" :key="q.topic"><td>{{ q.q }}</td><td class="cap">없음</td><td class="v-miss">빗나감</td></tr></tbody></table></div>
              <h2 id="e3">3. 놓친 쟁점</h2>
              <p v-if="!ev.missed.length" class="none">놓친 쟁점이 없어요.</p>
              <div v-else class="tbl"><table><thead><tr><th>리포트 쟁점</th><th>출처</th><th>놓친 이유</th></tr></thead>
                <tbody><tr v-for="i in ev.missed" :key="i.id"><td>{{ i.title }}</td><td class="cap"><a :href="i.url" target="_blank" rel="noopener">{{ i.publisher }}</a></td><td>캔버스에 관련 근거가 없음</td></tr></tbody></table></div>
              <h2 id="e4">4. 가정과 실적</h2>
              <div v-if="ev.checks.length" class="tbl"><table><thead><tr><th>항목</th><th class="n">기준 분기 값 유지 가정</th><th class="n">{{ A.next_quarter.label }} 실적</th><th class="n">차이</th><th>판정</th></tr></thead>
                <tbody><tr v-for="c in ev.checks" :key="c.tag"><td>{{ c.name }}</td><td class="n">{{ c.value.toLocaleString("ko-KR") }}</td><td class="n">{{ c.actual.toLocaleString("ko-KR") }}</td>
                  <td class="n">{{ c.diff > 0 ? "+" : "" }}{{ c.diff.toFixed(1) }}%</td><td :class="c.ok ? 'v-hit' : 'v-miss'">{{ c.ok ? "유지" : "재검토" }}</td></tr></tbody></table></div>
              <p v-else class="none">캔버스에 {{ A.focus }} 기준 분기 공시값이 없어 가정을 대조하지 않았어요.</p>
            </template>
          </article>
        </div>
      </template>
    </main>

    <!-- 내보내기 팝업 -->
    <div v-if="exportOpen && rv" class="scrim top xport-bg" @click.self="exportOpen = false">
      <section class="share xport" role="dialog" aria-modal="true" aria-label="내보내기">
        <header><h2>내보내기</h2><button class="x" aria-label="닫기" @click="exportOpen = false">✕</button></header>
        <div class="xseg" role="tablist" aria-label="내보내기 방식">
          <button role="tab" :aria-selected="xmode === 'prompt'" @click="xmode = 'prompt'">프롬프트로 추출</button>
          <button role="tab" :aria-selected="xmode === 'md'" @click="xmode = 'md'">Markdown으로 내보내기</button>
        </div>
        <pre class="xpre">{{ xtext }}</pre>
        <footer class="xfoot">
          <button v-if="xmode === 'prompt'" class="btn fill" @click="copyX">{{ copied ? "복사했어요" : "프롬프트 복사" }}</button>
          <button v-else class="btn fill" :disabled="!cur" @click="download(fileName('분석'), xtext)">Markdown 내려받기</button>
        </footer>
      </section>
    </div>
  </div>
</template>
