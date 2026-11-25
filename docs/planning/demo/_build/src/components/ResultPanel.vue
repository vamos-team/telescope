<script setup lang="ts">
// 오른쪽 결과 패널 (기본으로 열림): 프롬프트 추출 → 결과물 초안 → 애널리스트 질문 → 다음 분기 평가. 활동 기록은 왼쪽 사이드바 "활동 로그"
import { ref, computed } from "vue";
import { api, type Context, type Evidence } from "../api";
import { draft as makeDraft, questions as makeQuestions, evaluate, type Draft, type Question } from "../local/ai";
import { useCanvas } from "../stores/canvas";

const props = defineProps<{ tab: string }>();
const emit = defineEmits<{ tab: [t: string]; cite: [c: string] }>();
const cv = useCanvas();
const A = api.analysis;
const TABS = [["context", "프롬프트"], ["draft", "결과물"], ["questions", "질문"], ["eval", "평가"]];

const kind = ref(A.deliverables[0].id), extra = ref("");
const del = computed(() => A.deliverables.find((d) => d.id === kind.value)!);
const task = computed(() => `${del.value.name} 초안을 작성하세요. 목적: ${del.value.goal}.\n섹션: ${del.value.sections.map((s) => s.title).join(", ")}.\n`
  + "섹션마다 캔버스 근거를 인용하고, 근거가 없는 항목은 \"근거 부족\"으로 적습니다." + (extra.value.trim() ? `\n추가 요청: ${extra.value.trim()}` : ""));

const ctx = ref<Context | null>(null), dr = ref<Draft | null>(null), qs = ref<Question[] | null>(null);
const at = ref({ ctx: -1, dr: -1, qs: -1 });  // 생성 당시 활동 수. 캔버스가 바뀌면 다시 만들라고 알림
const stale = (k: "ctx" | "dr" | "qs") => at.value[k] >= 0 && at.value[k] !== cv.activity.length;
const copied = ref("");

async function extract() { await cv.flush(); ctx.value = await api.context(cv.sessionId, task.value); at.value.ctx = cv.activity.length; }
async function ev(): Promise<Evidence[]> { await cv.flush(); return api.evidence(cv.sessionId); }
async function genDraft() { dr.value = makeDraft(A, await ev(), kind.value); at.value.dr = cv.activity.length; }
async function genQs() { qs.value = makeQuestions(A, await ev()); at.value.qs = cv.activity.length; }
const result = computed(() => qs.value ? evaluate(A, qs.value, dr.value) : null);

async function copy(what: "text" | "json" | "md") {
  let s = "";
  if (what === "text" && ctx.value) s = ctx.value.messages.map((m) => `### ${m.role}\n${m.content}`).join("\n\n");
  if (what === "json" && ctx.value) s = JSON.stringify(ctx.value.messages, null, 2);
  if (what === "md" && dr.value) s = `# ${dr.value.deliverable.name}: ${dr.value.deliverable.goal}\n\n` + dr.value.sections.map((x) =>
    `## ${x.title}\n` + x.lines.map((l) => `- ${l.text}${l.cite.map((c) => ` [${c}]`).join("")} (${l.kind})`).join("\n")).join("\n\n");
  try { await navigator.clipboard.writeText(s); copied.value = what; setTimeout(() => (copied.value = ""), 1500); }
  catch { copied.value = "fail"; }
}
const chars = (s: string) => s.length.toLocaleString("ko-KR");
const stats = computed(() => {
  const ls = dr.value?.sections.flatMap((s) => s.lines) ?? [];
  return { cited: ls.filter((l) => l.cite.length).length, gap: ls.filter((l) => l.kind === "근거 부족").length };
});
</script>

<template>
  <aside class="rp" aria-label="결과">
    <header><div class="tabs" role="tablist">
      <button v-for="[k, n] in TABS" :key="k" role="tab" :aria-selected="tab === k" @click="emit('tab', k)">{{ n }}</button></div></header>

    <div class="body">
      <!-- 결과물 유형과 추가 요청은 프롬프트·결과물이 함께 쓴다 -->
      <div v-if="tab === 'context' || tab === 'draft'" class="opts">
        <label>결과물 유형 <select v-model="kind"><option v-for="d in A.deliverables" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
        <textarea v-if="tab === 'context'" v-model="extra" rows="2" placeholder="추가 요청 (선택) 예: 회수 기간을 보수적으로 잡아 주세요" aria-label="추가 요청" />
      </div>

      <section v-if="tab === 'context'">
        <div v-if="ctx" class="act"><button @click="copy('text')">{{ copied === "text" ? "복사함" : "프롬프트 복사" }}</button>
          <button @click="copy('json')">{{ copied === "json" ? "복사함" : "messages JSON" }}</button></div>
        <p v-if="!cv.cards.length" class="empty">캔버스에 자료를 올리면 LLM에 바로 넣을 수 있는 프롬프트로 정리합니다.</p>
        <p v-else-if="!ctx" class="empty">카드 내용, 출처, 연결, 수정 이력을 층으로 묶어 프롬프트를 만듭니다.</p>
        <p v-if="stale('ctx')" class="stale">캔버스가 바뀌었습니다. 다시 추출하세요.</p>
        <template v-if="ctx">
          <p class="note">앞 세 층(L0~L2)은 system, 뒤 두 층은 user 메시지입니다. 앞 층이 그대로면 LLM 서버가 prefix 캐시를 재사용합니다.</p>
          <details v-for="(v, k) in ctx.layers" :key="k" :open="k !== 'L0_system'" class="layer-box">
            <summary><b>{{ k }}</b><small>{{ ctx.hashes[k] }} / {{ chars(v) }}자</small>
              <span v-if="cv.lastChangedLayer === k" class="chg">최근 변경</span></summary>
            <pre>{{ v || "(비어 있음)" }}</pre>
          </details>
        </template>
      </section>

      <section v-else-if="tab === 'draft'">
        <div v-if="dr" class="act"><button @click="copy('md')">{{ copied === "md" ? "복사함" : "Markdown 복사" }}</button></div>
        <p v-if="!dr" class="empty">캔버스 근거로 {{ del.name }} 초안을 만듭니다. 문장마다 근거 ID가 붙고, 근거가 없는 항목은 따로 표시합니다.</p>
        <p v-if="stale('dr')" class="stale">캔버스가 바뀌었습니다. 다시 생성하세요.</p>
        <template v-if="dr">
          <h3>{{ dr.deliverable.name }}<small>{{ dr.deliverable.goal }}</small></h3>
          <p class="note">근거 인용 {{ stats.cited }}줄 / 근거 부족 {{ stats.gap }}항목. 근거 ID를 누르면 해당 카드로 이동합니다.</p>
          <div v-for="s in dr.sections" :key="s.title" class="sec"><h4>{{ s.title }}</h4>
            <p v-for="(l, i) in s.lines" :key="i" class="ln" :data-k="l.kind"><span class="vk">{{ l.kind }}</span>
              <span>{{ l.text }} <button v-for="c in l.cite" :key="c" class="cite" @click="emit('cite', c)">{{ c }}</button></span></p></div>
        </template>
      </section>

      <section v-else-if="tab === 'questions'">
        <p v-if="!qs" class="empty">캔버스 근거를 애널리스트 눈으로 읽고 나올 만한 질문과 준비할 근거를 정리합니다.</p>
        <p v-if="stale('qs')" class="stale">캔버스가 바뀌었습니다. 다시 생성하세요.</p>
        <p v-if="qs && !qs.length" class="empty">질문을 만들 근거가 없습니다. 공시 표나 IR·뉴스를 더 올려 보세요.</p>
        <ol v-if="qs?.length" class="qs"><li v-for="q in qs" :key="q.topic"><span class="lv" :data-lv="q.level">{{ q.level }}</span>
          <b>{{ q.q }}</b><small>{{ q.why }}</small>
          <span>준비할 근거 <button v-for="c in q.cite" :key="c" class="cite" @click="emit('cite', c)">{{ c }}</button></span></li></ol>
      </section>

      <section v-else-if="tab === 'eval'">
        <p class="note">평가 자료: {{ A.next_quarter.company }} {{ A.next_quarter.label }} 실적과 애널리스트 리포트 쟁점 ({{ A.next_quarter.date }} 이후 공개).
          생성 단계에서는 쓰지 않고 평가에만 씁니다. 리포트는 쟁점 요약과 출처 링크만 다룹니다.</p>
        <p v-if="!result" class="empty">먼저 질문을 만들면 다음 분기 쟁점과 견줘 점수를 냅니다. 결과물 초안이 있으면 초안의 가정도 함께 대조합니다.</p>
        <template v-if="result">
          <div class="kpi"><div><small>정밀도</small><b>{{ result.precision }}%</b><i :style="{ width: result.precision + '%' }" /></div>
            <div><small>재현율</small><b>{{ result.recall }}%</b><i :style="{ width: result.recall + '%' }" /></div></div>
          <h4>적중 {{ result.hit.length }}</h4>
          <p v-for="h in result.hit" :key="h.q.topic" class="ev ok">{{ h.q.q }}<small>쟁점: <a :href="h.issue.url" target="_blank" rel="noopener">{{ h.issue.title }}</a> / {{ h.issue.publisher }}</small></p>
          <h4>놓침 {{ result.missed.length }}</h4>
          <p v-for="i in result.missed" :key="i.id" class="ev miss">{{ i.title }}<small><a :href="i.url" target="_blank" rel="noopener">{{ i.publisher }}</a> / 캔버스에 관련 근거가 없음</small></p>
          <h4>빗나감 {{ result.off.length }}</h4>
          <p v-for="q in result.off" :key="q.topic" class="ev off">{{ q.q }}<small>{{ A.next_quarter.label }} 리포트에서 다루지 않음</small></p>
          <h4>초안 가정과 {{ A.next_quarter.label }} 실적</h4>
          <p v-if="!dr" class="empty">결과물 탭에서 초안을 만들면 가정을 대조합니다.</p>
          <table v-else-if="result.checks.length" class="g"><thead><tr><th>항목</th><th>가정</th><th>실적</th><th>차이</th><th>판정</th></tr></thead>
            <tbody><tr v-for="c in result.checks" :key="c.tag"><td>{{ c.name }}</td><td>{{ c.value.toLocaleString("ko-KR") }}</td>
              <td>{{ c.actual.toLocaleString("ko-KR") }}</td><td>{{ c.diff > 0 ? "+" : "" }}{{ c.diff.toFixed(1) }}%</td>
              <td :class="c.ok ? 'okc' : 'bad'">{{ c.ok ? "유지" : "재검토" }}</td></tr></tbody></table>
          <p v-else class="empty">초안에 {{ A.focus }} 공시값 가정이 없습니다.</p>
          <p class="note">판정 기준: 질문과 쟁점의 주제가 같으면 적중. 가정은 실적과 차이 5% 이내면 유지. 기준은 data/analysis.json에서 바꿉니다.</p>
        </template>
      </section>

    </div>
    <!-- 탭마다 주 동작 버튼: 패널 맨 아래 고정 -->
    <div class="go">
      <button v-if="tab === 'context'" :disabled="!cv.cards.length" @click="extract">프롬프트 추출</button>
      <button v-else-if="tab === 'draft'" :disabled="!cv.cards.length" @click="genDraft">{{ dr ? "초안 다시 생성" : "초안 생성" }}</button>
      <button v-else-if="tab === 'questions'" :disabled="!cv.cards.length" @click="genQs">{{ qs ? "질문 다시 생성" : "질문 생성" }}</button>
      <button v-else :disabled="!cv.cards.length" @click="genQs">{{ qs ? "질문 다시 생성 후 평가" : "질문 생성 후 평가" }}</button>
    </div>
  </aside>
</template>
