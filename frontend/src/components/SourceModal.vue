<script setup lang="ts">
// 자료 검색 화면 (상단 "자료 검색", ⌘K). 하단 탭 두 개(검색 / 인사이트, Tab으로 전환)와 입력창
// - 검색: 입력이 비면 대시보드 3개. Enter → 재무 데이터 리스트(공시 임베딩 검색 자리) + IR·AR 리스트. 재무 행을 누르면 원본 창에서 행을 골라 선택 목록에 담음
// - 인사이트: 입력이 비면 3사 비교 인사이트 + 관련 뉴스. Enter(확인) → 질문 개념에 닿는 AI 해석 비교 테이블 + 뉴스
// - 누르면 선택(입력창 위 칩 목록), "추가하기"로 캔버스에 올림. 길게 누르면 그 항목만 집어 캔버스로 바로 끌어 놓음(선택 목록과 별개)
// - 입력창 왼쪽 +: 사내 자료 파일을 골라 선택 목록에 담음
import { ref, watch, computed, nextTick, onMounted, onUnmounted } from "vue";
import { api, docTitle, KIND_LABEL, type SearchResult, type Doc, type Insight } from "../api";
import { useCanvas } from "../stores/canvas";
import { DASH_PANELS } from "../local/dashboard";
import DashPanel from "./DashPanel.vue";
import SourceOverlay from "./SourceOverlay.vue";

// 캔버스로 보내는 항목. 끌기 데이터(application/x-canvas)와 "추가하기"가 같은 모양을 씀
export interface Pick { key: string; label: string; tag: string; src: string; kind: "doc" | "table" | "compare" | "dash";
  id?: string; tableId?: string; rows?: number[]; cols?: number[]; tables?: string[]; panel?: string }

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: []; add: [items: Pick[]]; example: [] }>();
const cv = useCanvas();
const A = api.analysis;
const tab = ref<"search" | "insight">("search");
const q = ref(""), sq = ref(""), input = ref<HTMLInputElement>(), file = ref<HTMLInputElement>();
const dart = ref<SearchResult[]>([]), irar = ref<Doc[]>([]), news = ref<{ doc: Doc; why: string }[]>([]);
const basis = ref<{ companies: string[]; tags: string[] }>({ companies: [], tags: [] });
const nf = ref("전체");
const busy = ref(false), err = ref("");

// 추천 기준: 캔버스 표·문서 카드의 회사, 카드 개념 태그. 캔버스가 비면 분석 대상 회사(focus)
async function refreshBasis() {
  const cos = new Set<string>();
  for (const c of cv.cards) {
    if (c.kind === "table") cos.add((await api.table(c.tableId)).company);
    if (c.kind === "doc") { const co = api.docs.get(c.docId)?.company; if (co && !/3사|업계/.test(co)) cos.add(co); }
  }
  basis.value = { companies: cos.size ? [...cos] : [A.focus], tags: [...new Set(Object.values(cv.tags).flat())] };
}
// Enter(확인)를 누르면 검색어 확정. 입력을 다 지우면 대시보드·인사이트 홈으로
async function submit() {
  sq.value = q.value.trim();
  if (tab.value === "search" && sq.value) { dart.value = await api.searchQuestion(sq.value); irar.value = (await api.searchDocs(sq.value, ["ir", "ar"])).map((x) => x.doc); }
  news.value = sq.value ? await api.searchDocs(sq.value, ["news"]) : api.recommend(basis.value.companies, basis.value.tags, ["news"]);
}
watch(q, (v) => { if (!v.trim() && sq.value) submit(); });
watch(tab, () => { if (q.value.trim() !== sq.value) submit(); });
watch(() => props.open, async (open) => {
  if (!open) return;
  q.value = ""; sq.value = ""; tab.value = "search"; sel.value = []; dragging.value = false;
  await refreshBasis(); await submit();
  nextTick(() => input.value?.focus());
}, { immediate: true });

const coOk = (co: string) => nf.value === "전체" || (nf.value === "3사 공통" ? /3사|업계/.test(co) : co === nf.value);
const NF = ["전체", "SKT", "KT", "LGU+", "3사 공통"];
const newsShown = computed(() => news.value.filter((x) => coOk(x.doc.company)));
// 3사 비교 인사이트: 캔버스 개념과 겹치는 수로 정렬. 확정한 질문이 있으면 질문 개념에 닿는 것만
const insights = computed(() => {
  const qc = api.conceptsFor(sq.value);
  const scored = A.insights.map((i) => ({ i, s: i.concepts.filter((c) => basis.value.tags.includes(c)).length, hit: i.concepts.some((c) => qc.has(c)) }));
  return (sq.value ? scored.filter((x) => x.hit) : scored).sort((a, b) => b.s - a.s).map((x) => x.i);
});
const facts = (i: Insight) => api.peerFacts(i.concepts.slice(0, 2));
const pct = (a: number | null, b: number | null) => a == null || b == null || !a ? "—" : `${b >= a ? "+" : ""}${((b - a) / Math.abs(a) * 100).toFixed(1)}%`;
const num = (v: number | null) => v == null ? "—" : v.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const date = (d: string) => d.slice(2).replace(/-/g, ".");

// ---------- 항목 → Pick ----------
const docPick = (d: Doc): Pick => ({ key: "doc:" + d.id, label: docTitle(d), tag: KIND_LABEL[d.kind], src: d.kind, kind: "doc", id: d.id });
const tablePick = (r: SearchResult, rows = r.hits.map((h) => h.r), cols?: number[]): Pick =>
  ({ key: "tbl:" + r.table_id, label: `${r.company} ${r.title}`, tag: "공시", src: "dart", kind: "table", tableId: r.table_id, rows, cols });
const insPick = (i: Insight): Pick => ({ key: "ins:" + i.id, label: i.title, tag: "3사 비교", src: "viz", kind: "compare", tables: facts(i).tables });
const dashPick = (p: typeof DASH_PANELS[number]): Pick => ({ key: "dash:" + p.id, label: p.title, tag: "대시보드", src: "viz", kind: "dash", panel: p.id });

// ---------- 선택 (칩 목록) ----------
const sel = ref<Pick[]>([]);
const isSel = (k: string) => sel.value.some((x) => x.key === k);
const toggle = (p: Pick) => { sel.value = isSel(p.key) ? sel.value.filter((x) => x.key !== p.key) : [...sel.value, p]; };
const put = (p: Pick) => { sel.value = [...sel.value.filter((x) => x.key !== p.key), p]; };
function addAll() { if (!sel.value.length) return; emit("add", sel.value); sel.value = []; }

// 재무 데이터 행 → 원본 창에서 행 고르기 → 선택 목록에 담기
const detail = ref<SearchResult | null>(null);
function picked(_: string, rows: number[], cols: number[]) { put(tablePick(detail.value!, rows, cols)); detail.value = null; }

// ---------- 짧게 누르기 = 선택, 길게 누르기(400ms) = 그 항목만 집어 캔버스로 끌기 ----------
const hold = ref(""), armed = ref(""), dragging = ref(false);
let timer = 0;
function down(p: Pick, e: PointerEvent) {
  if (e.button !== 0 || (e.target as HTMLElement).closest("a,button")) return;
  hold.value = p.key; armed.value = "";
  clearTimeout(timer); timer = window.setTimeout(() => { armed.value = p.key; }, 400);
}
function up() { clearTimeout(timer); hold.value = ""; }
function click(p: Pick, e: MouseEvent) {
  if ((e.target as HTMLElement).closest("a,button")) return;
  if (armed.value === p.key) { armed.value = ""; return; }  // 길게 눌렀다 놓은 것은 선택이 아님
  toggle(p);
}
function dragStart(p: Pick, e: DragEvent) {
  if (armed.value !== p.key) { e.preventDefault(); return; }  // 길게 누른 항목만 끌 수 있음
  e.dataTransfer!.setData("application/x-canvas", JSON.stringify([p]));
  e.dataTransfer!.effectAllowed = "copy";
  setTimeout(() => { dragging.value = true; hold.value = ""; });  // 같은 틱에 화면을 바꾸면 브라우저가 끌기를 취소함
}
function dragEnd() { dragging.value = false; armed.value = ""; }
// 항목마다 붙이는 속성·이벤트 묶음
const pk = (p: Pick) => ({ class: ["pickable", { on: isSel(p.key), lift: hold.value === p.key && armed.value === p.key }], draggable: true, "data-src": p.src,
  onPointerdown: (e: PointerEvent) => down(p, e), onPointerup: up, onPointerleave: up, onClick: (e: MouseEvent) => click(p, e),
  onDragstart: (e: DragEvent) => dragStart(p, e), onDragend: dragEnd });

// ---------- 사내 자료 업로드 (+): 실제 서비스는 파서 모델이 본문·표를 요약하고 임베딩해 적재. 데모는 텍스트 파일만 읽어 앞부분을 요약으로 ----------
const KEYWORDS: Record<string, string[]> = { capex: ["capex", "설비투자", "설비"], ai_dc: ["데이터센터", "aidc", "gpu"], da: ["감가상각"],
  comm: ["수수료", "마케팅"], rev: ["매출", "영업수익"], opex: ["영업비용", "비용"], opi: ["영업이익"], dividend: ["배당"], labor: ["인건비"], debt: ["차입"],
  rp_purch: ["특수관계자", "내부거래", "계열사"] };
async function upload(e: Event) {
  const el = e.target as HTMLInputElement;
  busy.value = true; err.value = "";
  try {
    for (const [i, f] of Array.from(el.files ?? []).entries()) {
      const isText = /\.(txt|md|csv|json)$/i.test(f.name);
      const text = isText ? (await f.text()).replace(/\s+/g, " ").trim() : "";
      const figures = [...text.matchAll(/([가-힣A-Za-z]{2,12})\s*[:=]?\s*(-?\d[\d,]*(?:\.\d+)?)\s*(십억원|억원|백만원|조원|원|%)/g)].slice(0, 4)
        .map((m) => ({ label: m[1], value: +m[2].replace(/,/g, ""), unit: m[3], period: "업로드 자료" }));
      const hay = (f.name + text).toLowerCase().replace(/\s+/g, "");
      put(docPick(api.registerDoc({ id: `up${Date.now()}${i}`, kind: "internal", company: A.focus, title: f.name.replace(/\.[^.]+$/, ""),
        publisher: "업로드 (파서 요약)", date: new Date().toISOString().slice(0, 10), url: "", uploaded: true, figures,
        tags: Object.entries(KEYWORDS).filter(([, ks]) => ks.some((k) => hay.includes(k))).map(([id]) => id),
        summary: text ? text.slice(0, 180) + (text.length > 180 ? "…" : "")
          : `${f.name.split(".").pop()!.toUpperCase()} ${Math.ceil(f.size / 1024)}KB. 파서 모델을 연결하면 본문 요약과 표 수치가 이 자리에 들어감` })));
    }
  } catch (x) { err.value = `파일을 읽지 못했습니다: ${(x as Error).message}`; }
  finally { busy.value = false; el.value = ""; }
}

const PH = { search: "공시 재무 데이터와 IR·AR을 검색하고 Enter (예: SKT 감가상각비, KT 영업이익)",
  insight: "비교할 주제를 입력하고 Enter (예: 3사 영업이익, 감가상각비)" };
// ---------- 키보드: Tab·Shift+Tab 으로 검색 ↔ 인사이트 (Esc 닫기는 App) ----------
function onKey(e: KeyboardEvent) {
  if (!props.open || detail.value) return;
  if (e.key === "Tab" && !e.metaKey && !e.ctrlKey && !e.altKey) { e.preventDefault(); tab.value = tab.value === "search" ? "insight" : "search"; }
}
onMounted(() => window.addEventListener("keydown", onKey));
onUnmounted(() => window.removeEventListener("keydown", onKey));
</script>

<template>
  <div v-if="open" class="adder" :class="[dragging ? 'drag' : 'dim', 'tab-' + tab]" role="dialog" aria-label="자료 검색">
    <div class="dimbg" />
    <div class="corner">
      <button v-if="api.meta.example" class="ex" @click="emit('example')">예시 불러오기</button>
      <button class="x" aria-label="닫기 (Esc)" title="닫기 (Esc)" @click="emit('close')">✕</button>
    </div>
    <div v-if="tab === 'insight'" class="cofilter seg" role="radiogroup" aria-label="뉴스 회사">
      <button v-for="x in NF" :key="x" :aria-pressed="nf === x" @click="nf = x">{{ x }}</button></div>

    <main class="stage" :aria-hidden="dragging">
      <div class="inner">
        <!-- 검색: 대시보드 -->
        <template v-if="tab === 'search' && !sq">
          <div class="sech first"><h3>대시보드 <small>DART 공시, 억원</small></h3></div>
          <div class="dgrid">
            <section v-for="p in DASH_PANELS" :key="p.id" class="dcell" v-bind="pk(dashPick(p))">
              <span class="lk">대시보드</span>
              <header><b>{{ p.title }}</b><small>{{ p.sub }}</small></header>
              <DashPanel :panel="p.id" />
            </section>
          </div>
        </template>

        <!-- 검색: 결과 리스트 (재무 데이터 + IR·AR) -->
        <template v-else-if="tab === 'search'">
          <div class="sech first"><h3>재무 데이터 <small>{{ dart.length }}</small></h3></div>
          <div class="ilist">
            <article v-for="r in dart" :key="r.table_id" class="irow" v-bind="pk(tablePick(r))">
              <span class="lk">공시</span>
              <span class="src" data-src="dart">공시</span>
              <div class="tx"><b>{{ r.company }} {{ r.title }}</b><span>{{ r.hits.slice(0, 4).map((h) => h.label).join(", ") }}</span></div>
              <span class="meta">{{ [r.basis, r.period, r.unit].filter(Boolean).join(" · ") }}</span>
              <button @click.stop="detail = r">행 고르기</button>
            </article>
            <p v-if="!dart.length" class="none">일치하는 계정이 없습니다.</p>
          </div>
          <div class="sech"><h3>IR·AR <small>{{ irar.length }}</small></h3></div>
          <div class="ilist">
            <article v-for="d in irar" :key="d.id" class="irow" v-bind="pk(docPick(d))">
              <span class="lk">{{ KIND_LABEL[d.kind] }}</span>
              <span class="src" :data-src="d.kind">{{ KIND_LABEL[d.kind] }}</span>
              <div class="tx"><b :title="d.title">{{ docTitle(d) }}</b><span>{{ d.summary }}</span></div>
              <span class="meta">{{ d.company }} · {{ d.publisher }} · {{ date(d.date) }}</span>
              <a v-if="d.url" :href="d.url" target="_blank" rel="noopener" draggable="false">원본</a>
            </article>
            <p v-if="!irar.length" class="none">맞는 IR·AR 자료가 없습니다.</p>
          </div>
        </template>

        <!-- 인사이트: AI 해석 비교 테이블 + 관련 뉴스 -->
        <template v-else>
          <div class="sech first"><h3>{{ sq ? `'${sq}' 비교 분석` : "3사 비교 인사이트" }} <small>{{ insights.length }}</small></h3><span class="tag ai">AI 해석</span></div>
          <div class="icards row">
            <section v-for="i in insights" :key="i.id" class="icard" v-bind="pk(insPick(i))">
              <span class="lk">3사 비교</span>
              <h4>{{ i.title }}</h4>
              <div>
                <div class="lab2">사실 <span class="tag">공시</span><span class="tag">계산</span><small>십억원</small></div>
                <table v-for="f in facts(i).facts" :key="f.concept" class="g facts">
                  <thead><tr><th>{{ f.name }}</th><th class="n">1Q25</th><th class="n">1Q26</th><th class="n">증감</th></tr></thead>
                  <tbody><tr v-for="r in f.rows" :key="r.company" :class="{ b: basis.companies.includes(r.company) }">
                    <td>{{ r.company }}</td><td class="n">{{ num(r.prv) }}</td><td class="n">{{ num(r.cur) }}</td><td class="n">{{ pct(r.prv, r.cur) }}</td></tr></tbody>
                </table>
              </div>
              <div class="aip"><div class="ai-lab">AI 해석</div><p :title="i.ai">{{ i.ai }}</p>
                <div class="nrefs"><template v-for="n in i.news.slice(0, 2)" :key="n"><a v-if="api.docs.get(n)" :href="api.docs.get(n)!.url" target="_blank" rel="noopener" draggable="false">
                  {{ api.docs.get(n)!.publisher }} {{ api.docs.get(n)!.date.slice(5) }} · {{ api.docs.get(n)!.title }}</a></template></div></div>
            </section>
            <p v-if="!insights.length" class="none">입력한 주제와 닿는 비교 분석이 없습니다.</p>
          </div>
          <div class="sech"><h3>관련 뉴스 <small>{{ newsShown.length }}</small></h3></div>
          <div class="hs">
            <article v-for="{ doc: d, why } in newsShown" :key="d.id" class="dcard" v-bind="pk(docPick(d))">
              <span class="lk">뉴스</span>
              <b class="ti" :title="d.title">{{ docTitle(d) }}</b>
              <p class="su">{{ d.summary }}</p>
              <small class="why">{{ why }}</small>
              <div class="ft"><span class="meta">{{ d.company }} · {{ d.publisher }} · {{ date(d.date) }}</span>
                <a v-if="d.url" :href="d.url" target="_blank" rel="noopener" draggable="false">원본</a></div>
            </article>
            <p v-if="!newsShown.length" class="none">맞는 뉴스가 없습니다.</p>
          </div>
        </template>
      </div>
    </main>

    <!-- 하단: 탭 + 선택 칩 + 입력창 (+ 업로드 / 추가하기) -->
    <div class="dock">
      <div class="tabs3" role="tablist" aria-label="자료 검색 보기">
        <button role="tab" :aria-selected="tab === 'search'" @click="tab = 'search'">검색</button>
        <button role="tab" :aria-selected="tab === 'insight'" @click="tab = 'insight'">인사이트</button>
      </div>
      <div v-if="sel.length" class="chips2" aria-label="선택한 항목">
        <span v-for="p in sel" :key="p.key" class="chip" :data-src="p.src"><b :title="p.label">{{ p.label }}</b>
          <button :aria-label="p.label + ' 선택 해제'" @click="toggle(p)">✕</button></span>
      </div>
      <form class="cin" @submit.prevent="submit">
        <button type="button" class="plus" :title="busy ? '요약하는 중' : '사내 자료 첨부'" aria-label="사내 자료 첨부" @click="file?.click()">+</button>
        <input ref="file" class="hide" type="file" multiple accept=".txt,.md,.csv,.json,.pdf,.xlsx,.docx,.hwp" @change="upload" />
        <input ref="input" v-model="q" type="search" :placeholder="PH[tab]" :aria-label="PH[tab]" autocomplete="off" />
        <button type="button" class="addb" :disabled="!sel.length" @click="addAll">추가하기{{ sel.length ? ` ${sel.length}` : "" }}</button>
      </form>
      <small v-if="err" class="err">{{ err }}</small>
    </div>
    <SourceOverlay v-if="detail" :table-id="detail.table_id" :match="detail.hits.map((h) => h.r)" pick @close="detail = null" @add="picked" />
  </div>
</template>
