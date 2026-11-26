<script setup lang="ts">
// 오른쪽 결과 패널: 상단 "생성하기"를 누르면 열림. 생성(캔버스 → 컨텍스트 글) → 질문 → 평가를 한 번에 진행
// 생성: 캔버스의 카드 · 연결 · 프롬프트 · 메모를 글로 정리. 고칠 수 있고(편집), 복사 · Markdown으로 내보냄. 근거 부족 판정은 하지 않음
// 프로세스 바의 끝난 단계를 누르면 그 파트로. 머리의 접기 버튼으로 얇은 레일로 접음 (생성하기를 다시 누르면 펼침)
import { ref, computed, watch } from "vue";
import { api } from "../api";
import { contextNote, questions as makeQuestions, assumptions as makeAssumptions, evaluate, type Question, type Assumption } from "../local/ai";
import { useCanvas, quarterOf } from "../stores/canvas";

const props = defineProps<{ run: number; open: boolean }>();  // run: 생성하기를 누를 때마다 오름
const emit = defineEmits<{ cite: [c: string]; toggle: [] }>();
const cv = useCanvas();
const A = api.analysis;
const STEPS = [["note", "생성"], ["questions", "질문"], ["eval", "평가"]] as const;
type Step = typeof STEPS[number][0];
const part = ref<Step>("note");
const done = ref(new Set<Step>()), doing = ref<Step | null>(null);

const note = ref(""), generated = ref(""), editing = ref(false);
const qs = ref<Question[] | null>(null), assume = ref<Assumption[]>([]);
const result = computed(() => qs.value ? evaluate(A, qs.value, assume.value) : null);
const at = ref(-1);
const stale = computed(() => at.value >= 0 && at.value !== cv.rev);
const edited = computed(() => !!note.value && note.value !== generated.value);
// 진행 막대: 끝난 단계 수 + 진행 중이면 반 칸
const progress = computed(() => (done.value.size + (doing.value ? 0.5 : 0)) / STEPS.length * 100);

const tick = () => new Promise((ok) => setTimeout(ok, 350));  // ponytail: 목업 AI는 즉시 끝나 진행이 안 보여 단계마다 짧게 쉼. LLM 연결 뒤 삭제
async function generate() {
  if (!cv.cards.length || doing.value) return;
  if (edited.value && !confirm("고친 정리 글을 새로 만든 글로 덮어쓸까요?")) return;
  done.value = new Set(); qs.value = null; editing.value = false;
  await cv.flush();
  doing.value = "note"; part.value = "note"; await tick();
  const sid = cv.sessionId;
  const text = await contextNote({ canvas: cv.canvasName, quarter: quarterOf(cv.meta), cards: cv.cards, links: cv.links, memos: cv.memos,
    prompts: cv.userPrompts, sessionId: sid, title: cv.cardTitle });
  if (sid !== cv.sessionId) return;
  note.value = generated.value = text; done.value.add("note");
  doing.value = "questions"; await tick();
  const ev = await api.evidence(sid);
  qs.value = makeQuestions(A, ev); assume.value = makeAssumptions(A, ev); done.value.add("questions");
  doing.value = "eval"; await tick();
  done.value.add("eval"); doing.value = null; at.value = cv.rev;
}
watch(() => props.run, generate, { immediate: true });

// ---------- 보기: Markdown을 제목 · 목록 · 문단으로, [근거ID]는 누르면 카드로 가는 버튼 ----------
const blocks = computed(() => note.value.split("\n").filter((l) => l.trim()).map((l) => {
  const m = l.match(/^(#{1,3}) (.*)$/), li = l.match(/^(\s*)- (.*)$/);
  const kind = m ? `h${m[1].length}` : li ? (li[1] ? "li2" : "li") : "p", body = m ? m[2] : li ? li[2] : l;
  return { kind, parts: body.split(/(\[[^\]\s]+\])/).map((x) => x.startsWith("[") && x.endsWith("]") ? { cite: x.slice(1, -1) } : { t: x }) };
}));
const isCite = (c: string) => /^(doc:|dash:)|#r\d+c\d+$/.test(c);

// ---------- 내보내기 ----------
const day = () => new Date().toISOString().slice(0, 10).replace(/-/g, "");
const fileName = (what: string, ext: string) => `${cv.canvasName}_${what}_${day()}.${ext}`.replace(/[\\/:*?"<>|]/g, "_");
function download(name: string, text: string) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=utf-8" }));
  a.download = name; a.click(); URL.revokeObjectURL(a.href);
}
const qsMd = () => !qs.value ? "" : `# 예상 애널리스트 질문\n\n캔버스: ${cv.canvasName}\n\n` + qs.value.map((q, i) =>
  `## ${i + 1}. ${q.q}\n- 중요도: ${q.level}\n- 이유: ${q.why}\n- 준비할 근거: ${q.cite.map((c) => `[${c}]`).join(" ") || "없음"}`).join("\n\n") + "\n";
const copied = ref(false);
async function copy() {
  try { await navigator.clipboard.writeText(note.value); copied.value = true; setTimeout(() => (copied.value = false), 1500); }
  catch { /* 클립보드 권한 없음 */ }
}
</script>

<template>
  <!-- 접힌 레일: 펴기 버튼 + 단계 점 -->
  <aside v-if="!open" class="rp rail" aria-label="생성 결과 (접힘)">
    <button class="x" title="결과 패널 펴기" aria-label="결과 패널 펴기" @click="emit('toggle')">«</button>
    <span v-for="[k, n] in STEPS" :key="k" class="rdot" :data-st="done.has(k) ? 'done' : doing === k ? 'doing' : 'wait'" :title="n" />
    <b class="rlab">생성 결과</b>
  </aside>
  <aside v-else class="rp" aria-label="생성 결과">
    <header class="rph">
      <button class="x" title="결과 패널 접기" aria-label="결과 패널 접기" @click="emit('toggle')">»</button>
      <b class="rtitle">캔버스 컨텍스트</b>
      <button class="primary sm" :disabled="!cv.cards.length || !!doing" @click="generate">다시 생성</button>
    </header>
    <!-- 프로세스 바: 가로 트랙 + 단계 점. 끝난 단계는 눌러서 전환 -->
    <div class="pbar" role="tablist" aria-label="생성 단계">
      <div class="track"><i :style="{ width: progress + '%' }" /></div>
      <button v-for="([k, n], i) in STEPS" :key="k" role="tab" :aria-selected="part === k" :data-st="done.has(k) ? 'done' : doing === k ? 'doing' : 'wait'"
        :disabled="!done.has(k)" :style="{ left: (i + 0.5) / STEPS.length * 100 + '%' }" @click="part = k">
        <span class="dot">{{ done.has(k) ? "✓" : i + 1 }}</span><span class="nm">{{ n }}<small v-if="doing === k"> 진행 중</small></span></button>
    </div>
    <p v-if="stale" class="stale">캔버스가 바뀌었습니다. 다시 생성하세요.</p>
    <p v-if="!cv.cards.length" class="empty">캔버스에 근거 카드를 올린 뒤 생성하세요.</p>

    <div class="body">
      <section v-if="part === 'note' && note" class="cnote">
        <div class="act"><small v-if="edited" class="ed">고친 글</small>
          <button :aria-pressed="editing" @click="editing = !editing">{{ editing ? "보기" : "편집" }}</button>
          <button @click="copy">{{ copied ? "복사함" : "복사" }}</button>
          <button @click="download(fileName('컨텍스트', 'md'), note)">Markdown</button></div>
        <textarea v-if="editing" v-model="note" class="ntext2" aria-label="캔버스 정리 글" spellcheck="false" />
        <div v-else class="md">
          <component :is="b.kind === 'li' || b.kind === 'li2' ? 'p' : b.kind === 'p' ? 'p' : b.kind" v-for="(b, i) in blocks" :key="i" :class="b.kind">
            <template v-for="(x, j) in b.parts" :key="j"><button v-if="'cite' in x && isCite(x.cite!)" class="cite" @click="emit('cite', x.cite!)">{{ x.cite }}</button>
              <template v-else>{{ 'cite' in x ? `[${x.cite}]` : x.t }}</template></template>
          </component>
        </div>
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
        <h4>기준 분기 값 유지 가정과 {{ A.next_quarter.label }} 실적</h4>
        <table v-if="result.checks.length" class="g"><thead><tr><th>항목</th><th>가정</th><th>실적</th><th>차이</th><th>판정</th></tr></thead>
          <tbody><tr v-for="c in result.checks" :key="c.tag"><td>{{ c.name }}</td><td>{{ c.value.toLocaleString("ko-KR") }}</td>
            <td>{{ c.actual.toLocaleString("ko-KR") }}</td><td>{{ c.diff > 0 ? "+" : "" }}{{ c.diff.toFixed(1) }}%</td>
            <td :class="c.ok ? 'okc' : 'bad'">{{ c.ok ? "유지" : "재검토" }}</td></tr></tbody></table>
        <p v-else class="empty">캔버스에 {{ A.focus }} 기준 분기 공시값이 없어 가정을 대조하지 않았습니다.</p>
      </section>
    </div>
  </aside>
</template>
