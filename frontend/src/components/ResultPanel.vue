<script setup lang="ts">
// 오른쪽 결과 패널: 상단 "생성하기"를 누르면 열려 화면 절반을 차지함. 캔버스 컨텍스트로 결과물 → 질문 → 평가를 한 번에 진행
// 프로세스 바의 단계를 누르면 그 파트로 전환. 내보내기: 결과물 PDF·Markdown, 질문 Markdown. 프롬프트 복사는 부가 기능
import { ref, computed, watch } from "vue";
import { api } from "../api";
import { draft as makeDraft, questions as makeQuestions, evaluate, type Draft, type Question } from "../local/ai";
import { useCanvas } from "../stores/canvas";

const props = defineProps<{ run: number }>();  // 생성하기를 누를 때마다 오름
const emit = defineEmits<{ cite: [c: string] }>();
const cv = useCanvas();
const A = api.analysis;
const STEPS = [["draft", "결과물"], ["questions", "질문"], ["eval", "평가"]] as const;
type Step = typeof STEPS[number][0];
const part = ref<Step>("draft");
const done = ref(new Set<Step>()), doing = ref<Step | null>(null);

const kind = ref(A.deliverables[0].id);
const dr = ref<Draft | null>(null), qs = ref<Question[] | null>(null);
const result = computed(() => qs.value ? evaluate(A, qs.value, dr.value) : null);
// 생성 당시의 근거 버전(cv.rev)과 결과물 종류. 달라지면 다시 생성하라고 알림
const sig = () => `${cv.rev}|${kind.value}`;
const at = ref("");
const stale = computed(() => !!at.value && at.value !== sig());

const tick = () => new Promise((ok) => setTimeout(ok, 350));  // ponytail: 목업 AI는 즉시 끝나 진행이 안 보여 단계마다 짧게 쉼. LLM 연결 뒤 삭제
async function generate() {
  if (!cv.cards.length) return;
  done.value = new Set(); dr.value = null; qs.value = null;
  await cv.flush();
  const ev = await api.evidence(cv.sessionId);
  doing.value = "draft"; part.value = "draft"; await tick();
  dr.value = makeDraft(A, ev, kind.value, cv.userPrompts); done.value.add("draft");
  doing.value = "questions"; await tick();
  qs.value = makeQuestions(A, ev); done.value.add("questions");
  doing.value = "eval"; await tick();
  done.value.add("eval"); doing.value = null; at.value = sig();
}
watch(() => props.run, generate, { immediate: true });

// ---------- 내보내기 ----------
const day = () => new Date().toISOString().slice(0, 10).replace(/-/g, "");
const fileName = (what: string, ext: string) => `${cv.canvasName}_${what}_${day()}.${ext}`.replace(/[\\/:*?"<>|]/g, "_");
function download(name: string, text: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
  a.download = name; a.click(); URL.revokeObjectURL(a.href);
}
const draftMd = () => !dr.value ? "" : `# ${dr.value.deliverable.name}: ${dr.value.deliverable.goal}\n\n캔버스: ${cv.canvasName}\n\n` + dr.value.sections.map((x) =>
  `## ${x.title}\n` + x.lines.map((l) => `- ${l.text}${l.cite.map((c) => ` [${c}]`).join("")} (${l.kind})${l.hint ? ` — 지시: ${l.hint}` : ""}`).join("\n")).join("\n\n") + "\n";
const qsMd = () => !qs.value ? "" : `# 예상 애널리스트 질문\n\n캔버스: ${cv.canvasName}\n\n` + qs.value.map((q, i) =>
  `## ${i + 1}. ${q.q}\n- 중요도: ${q.level}\n- 이유: ${q.why}\n- 준비할 근거: ${q.cite.map((c) => `[${c}]`).join(" ") || "없음"}`).join("\n\n") + "\n";
// PDF: 인쇄용 CSS(@media print, body.printing)로 결과물 본문만 남기고 브라우저 인쇄 → PDF로 저장. 문서 제목이 기본 파일명
function pdf() {
  const t = document.title;
  document.title = fileName(dr.value!.deliverable.name, "pdf").replace(/\.pdf$/, "");
  document.body.classList.add("printing");
  window.addEventListener("afterprint", () => { document.body.classList.remove("printing"); document.title = t; }, { once: true });
  window.print();
}
const copied = ref(false);
async function copyPrompt() {
  await cv.flush();
  const ctx = await api.context(cv.sessionId, `${A.deliverables.find((d) => d.id === kind.value)!.name} 초안을 작성하세요.`);
  try { await navigator.clipboard.writeText(ctx.messages.map((m) => `### ${m.role}\n${m.content}`).join("\n\n")); copied.value = true; setTimeout(() => (copied.value = false), 1500); }
  catch { /* 클립보드 권한 없음 */ }
}
const stats = computed(() => {
  const ls = dr.value?.sections.flatMap((s) => s.lines) ?? [];
  return { cited: ls.filter((l) => l.cite.length).length, gap: ls.filter((l) => l.kind === "근거 부족").length };
});
</script>

<template>
  <aside class="rp" aria-label="생성 결과">
    <header class="rph">
      <label>결과물 <select v-model="kind" aria-label="결과물 종류"><option v-for="d in A.deliverables" :key="d.id" :value="d.id">{{ d.name }}</option></select></label>
      <button class="line sm" @click="copyPrompt">{{ copied ? "복사함" : "프롬프트 복사" }}</button>
      <button class="primary sm" :disabled="!cv.cards.length || !!doing" @click="generate">다시 생성</button>
    </header>
    <!-- 프로세스 바: 단계 상태(진행 중·완료) + 누르면 그 파트로 -->
    <ol class="steps" role="tablist" aria-label="생성 단계">
      <li v-for="([k, n], i) in STEPS" :key="k">
        <button role="tab" :aria-selected="part === k" :data-st="done.has(k) ? 'done' : doing === k ? 'doing' : 'wait'" :disabled="!done.has(k)" @click="part = k">
          <span class="no">{{ done.has(k) ? "✓" : i + 1 }}</span>{{ n }}<small v-if="doing === k">진행 중</small></button>
      </li>
    </ol>
    <p v-if="stale" class="stale">캔버스나 결과물 종류가 바뀌었습니다. 다시 생성하세요.</p>
    <p v-if="!cv.cards.length" class="empty">캔버스에 근거 카드를 올린 뒤 생성하세요.</p>

    <div class="body">
      <section v-if="part === 'draft' && dr" class="print-area">
        <div class="act"><button @click="pdf">PDF</button><button @click="download(fileName(dr.deliverable.name, 'md'), draftMd())">Markdown</button></div>
        <h3>{{ dr.deliverable.name }}<small>{{ dr.deliverable.goal }}</small></h3>
        <p class="note">근거 인용 {{ stats.cited }}줄 / 근거 부족 {{ stats.gap }}항목</p>
        <div v-for="s in dr.sections" :key="s.title" class="sec" :class="{ ins: s.title === '작성 지시' }"><h4>{{ s.title }}</h4>
          <p v-for="(l, i) in s.lines" :key="i" class="ln" :data-k="l.kind"><span class="vk">{{ l.kind }}</span>
            <span>{{ l.text }} <button v-for="c in l.cite" :key="c" class="cite" @click="emit('cite', c)">{{ c }}</button>
              <em v-if="l.hint" class="hint">지시: {{ l.hint }}</em></span></p></div>
      </section>

      <section v-else-if="part === 'questions' && qs">
        <div v-if="qs.length" class="act"><button @click="download(fileName('예상질문', 'md'), qsMd())">Markdown</button></div>
        <p v-if="!qs.length" class="empty">질문을 만들 근거가 없습니다.</p>
        <ol v-else class="qs"><li v-for="q in qs" :key="q.topic"><span class="lv" :data-lv="q.level">{{ q.level }}</span>
          <b>{{ q.q }}</b><small>{{ q.why }}</small>
          <span>준비할 근거 <button v-for="c in q.cite" :key="c" class="cite" @click="emit('cite', c)">{{ c }}</button></span></li></ol>
      </section>

      <section v-else-if="part === 'eval' && result">
        <div class="kpi"><div><small>정밀도</small><b>{{ result.precision }}%</b><i :style="{ width: result.precision + '%' }" /></div>
          <div><small>재현율</small><b>{{ result.recall }}%</b><i :style="{ width: result.recall + '%' }" /></div></div>
        <h4>적중 {{ result.hit.length }}</h4>
        <p v-for="h in result.hit" :key="h.q.topic" class="ev ok">{{ h.q.q }}<small>쟁점: <a :href="h.issue.url" target="_blank" rel="noopener">{{ h.issue.title }}</a> / {{ h.issue.publisher }}</small></p>
        <h4>놓침 {{ result.missed.length }}</h4>
        <p v-for="i in result.missed" :key="i.id" class="ev miss">{{ i.title }}<small><a :href="i.url" target="_blank" rel="noopener">{{ i.publisher }}</a> / 캔버스에 관련 근거가 없음</small></p>
        <h4>빗나감 {{ result.off.length }}</h4>
        <p v-for="q in result.off" :key="q.topic" class="ev off">{{ q.q }}<small>{{ A.next_quarter.label }} 리포트에서 다루지 않음</small></p>
        <h4>결과물 가정과 {{ A.next_quarter.label }} 실적</h4>
        <table v-if="result.checks.length" class="g"><thead><tr><th>항목</th><th>가정</th><th>실적</th><th>차이</th><th>판정</th></tr></thead>
          <tbody><tr v-for="c in result.checks" :key="c.tag"><td>{{ c.name }}</td><td>{{ c.value.toLocaleString("ko-KR") }}</td>
            <td>{{ c.actual.toLocaleString("ko-KR") }}</td><td>{{ c.diff > 0 ? "+" : "" }}{{ c.diff.toFixed(1) }}%</td>
            <td :class="c.ok ? 'okc' : 'bad'">{{ c.ok ? "유지" : "재검토" }}</td></tr></tbody></table>
        <p v-else class="empty">결과물에 {{ A.focus }} 공시값 가정이 없습니다.</p>
      </section>
    </div>
  </aside>
</template>
