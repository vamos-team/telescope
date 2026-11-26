<script setup lang="ts">
// 자료 검색 화면 (상단 "자료 검색", ⌘K)
// - 입력이 비면 하단 탭 3개 (Tab · Shift+Tab 순환): 대시보드(한눈에 + 자산 계정) / 회사 재무(손익 · 재무상태 · 현금흐름 · 설비투자 · 동종사 비교) / 인사이트(3사 비교 + 관련 뉴스)
// - 입력하는 즉시 통합 검색: 공시 재무 데이터 + IR·AR + 뉴스 (실제 서비스는 임베딩 검색 자리. 화면은 공시 검색과 문서 검색을 같은 q로 함께 부름)
// - 누르면 선택(입력창 위 칩), 입력창 오른쪽 "추가하기"로 캔버스에 올림. 길게 누르면 그 항목만 집어 캔버스로 끌어 놓음
// - 입력창 왼쪽 +: 사내 자료 파일을 골라 선택 목록에 담음
import { ref, watch, computed, nextTick, onMounted, onUnmounted } from "vue";
import { api, docTitle, KIND_LABEL, type SearchResult, type Doc, type Insight } from "../api";
import { useCanvas } from "../stores/canvas";
import { LAYOUT, panelOf } from "../local/dashboard";
import DashPanel from "./DashPanel.vue";
import SourceOverlay from "./SourceOverlay.vue";

// 캔버스로 보내는 항목. 끌기 데이터(application/x-canvas)와 "추가하기"가 같은 모양을 씀
export interface Pick { key: string; label: string; tag: string; src: string; kind: "doc" | "table" | "compare" | "dash";
  id?: string; tableId?: string; rows?: number[]; cols?: number[]; tables?: string[]; panel?: string }

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: []; add: [items: Pick[]]; example: [] }>();
const cv = useCanvas();
const A = api.analysis;
const TABS = [["dash", "대시보드"], ["fin", "회사 재무"], ["insight", "인사이트"]] as const;
type Tab = typeof TABS[number][0];
const tab = ref<Tab>("dash"), ftab = ref("pl"), period = ref<"y" | "q">("y");
const q = ref(""), sq = ref(""), input = ref<HTMLInputElement>(), file = ref<HTMLInputElement>();
const dart = ref<SearchResult[]>([]), irar = ref<Doc[]>([]), found = ref<Doc[]>([]);
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
// 통합 검색: 입력하는 즉시(짧게 묶어서) 공시 · IR·AR · 뉴스를 함께 찾음. 입력을 다 지우면 탭 화면으로
let timer = 0;
async function search() {
  const s = q.value.trim(); sq.value = s;
  if (!s) return;
  const [d, ir, nw] = await Promise.all([api.searchQuestion(s), api.searchDocs(s, ["ir", "ar"]), api.searchDocs(s, ["news"])]);
  if (q.value.trim() !== s) return;  // 그사이 입력이 바뀜
  dart.value = d; irar.value = ir.map((x) => x.doc); found.value = nw.map((x) => x.doc);
}
watch(q, () => { clearTimeout(timer); if (!q.value.trim()) { sq.value = ""; return; } timer = window.setTimeout(search, 150); });
watch(() => props.open, async (open) => {
  if (!open) return;
  q.value = ""; sq.value = ""; tab.value = "dash"; sel.value = []; dragging.value = false;
  await refreshBasis();
  nextTick(() => input.value?.focus());
}, { immediate: true });

// 인사이트 탭: 관련 뉴스는 캔버스 회사 · 개념과 겹치는 순, 회사 필터
const NF = ["전체", "SKT", "KT", "LGU+", "3사 공통"];
const coOk = (co: string) => nf.value === "전체" || (nf.value === "3사 공통" ? /3사|업계/.test(co) : co === nf.value);
const news = computed(() => api.recommend(basis.value.companies, basis.value.tags, ["news"]).map((x) => x.doc).filter((d) => coOk(d.company)));
// 3사 비교 인사이트(분기, 공시 표 → 비교 카드): 캔버스 개념과 겹치는 수로 정렬
const insights = computed(() => A.insights.map((i) => ({ i, s: i.concepts.filter((c) => basis.value.tags.includes(c)).length })).sort((a, b) => b.s - a.s).map((x) => x.i));
const facts = (i: Insight) => api.peerFacts(i.concepts.slice(0, 2));
const pct = (a: number | null, b: number | null) => a == null || b == null || !a ? "—" : `${b >= a ? "+" : ""}${((b - a) / Math.abs(a) * 100).toFixed(1)}%`;
const num = (v: number | null) => v == null ? "—" : v.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });
const date = (d: string) => d.slice(2).replace(/-/g, ".");
const finPanels = computed(() => { const t = LAYOUT.fin[ftab.value]; return period.value === "q" && t.q ? t.q : t.y; });
const span = (id: string) => `s${panelOf(id)?.span ?? 12}`;

// ---------- 항목 → Pick ----------
const docPick = (d: Doc): Pick => ({ key: "doc:" + d.id, label: docTitle(d), tag: KIND_LABEL[d.kind], src: d.kind, kind: "doc", id: d.id });
const tablePick = (r: SearchResult, rows = r.hits.map((h) => h.r), cols?: number[]): Pick =>
  ({ key: "tbl:" + r.table_id, label: `${r.company} ${r.title}`, tag: "공시", src: "dart", kind: "table", tableId: r.table_id, rows, cols });
const insPick = (i: Insight): Pick => ({ key: "ins:" + i.id, label: i.title, tag: "3사 비교", src: "viz", kind: "compare", tables: facts(i).tables });
const dashPick = (id: string): Pick => ({ key: "dash:" + id, label: panelOf(id)?.title ?? id, tag: "대시보드", src: "viz", kind: "dash", panel: id });

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
// 차트 안의 조작 버튼(보기 전환 등)은 .nodrag 안에 있어 선택하지 않음
const hold = ref(""), armed = ref(""), dragging = ref(false);
let htimer = 0;
const inner = (e: Event) => !!(e.target as HTMLElement).closest("a,button,input,label,.nodrag");
function down(p: Pick, e: PointerEvent) {
  if (e.button !== 0 || inner(e)) return;
  hold.value = p.key; armed.value = "";
  clearTimeout(htimer); htimer = window.setTimeout(() => { armed.value = p.key; }, 400);
}
function up() { clearTimeout(htimer); hold.value = ""; }
function click(p: Pick, e: MouseEvent) {
  if (inner(e)) return;
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
const pk = (p: Pick) => ({ class: ["pickable", { on: isSel(p.key), lift: hold.value === p.key && armed.value === p.key }], draggable: true, "data-src": p.src,
  onPointerdown: (e: PointerEvent) => down(p, e), onPointerup: up, onPointerleave: up, onClick: (e: MouseEvent) => click(p, e),
  onDragstart: (e: DragEvent) => dragStart(p, e), onDragend: dragEnd });
const noImg = (e: Event) => { const el = e.target as HTMLImageElement; el.parentElement?.classList.add("noimg"); el.remove(); };

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

const PH = "공시 · IR · AR · 뉴스를 한 번에 검색 (예: SKT 감가상각비, KT 영업이익)";
// ---------- 키보드: 입력이 비었을 때 Tab · Shift+Tab 으로 탭 순환 (Esc 닫기는 App) ----------
function onKey(e: KeyboardEvent) {
  if (!props.open || detail.value || sq.value) return;
  if (e.key === "Tab" && !e.metaKey && !e.ctrlKey && !e.altKey) {
    e.preventDefault();
    const i = TABS.findIndex(([k]) => k === tab.value);
    tab.value = TABS[(i + (e.shiftKey ? TABS.length - 1 : 1)) % TABS.length][0];
  }
}
onMounted(() => window.addEventListener("keydown", onKey));
onUnmounted(() => window.removeEventListener("keydown", onKey));
</script>

<template>
  <div v-if="open" class="adder" :class="[dragging ? 'drag' : 'dim']" role="dialog" aria-label="자료 검색">
    <div class="dimbg" />
    <div class="corner">
      <button v-if="api.meta.example" class="ex" @click="emit('example')">예시 불러오기</button>
      <button class="x" aria-label="닫기 (Esc)" title="닫기 (Esc)" @click="emit('close')">✕</button>
    </div>

    <main class="stage" :aria-hidden="dragging">
      <div class="inner dz">
        <!-- 통합 검색 결과 -->
        <template v-if="sq">
          <div class="sech first"><h3>공시 재무 데이터 <small>{{ dart.length }}</small></h3></div>
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
          <div class="sech"><h3>뉴스 <small>{{ found.length }}</small></h3></div>
          <div class="news">
            <article v-for="d in found" :key="d.id" class="ncard" v-bind="pk(docPick(d))">
              <span class="lk">뉴스</span>
              <div class="nimg"><img v-if="d.img" :src="d.img" alt="" loading="lazy" referrerpolicy="no-referrer" draggable="false" @error="noImg"><span>{{ d.publisher.slice(0, 1) }}</span></div>
              <div class="ntext"><div class="nm">{{ d.publisher }}<span class="dotsep">·</span>{{ date(d.date) }}</div>
                <div class="nt" :title="d.title">{{ d.title }}</div>
                <div class="nft"><span class="tag">{{ /3사|업계/.test(d.company) ? "3사 공통" : d.company }}</span><a v-if="d.url" :href="d.url" target="_blank" rel="noopener" draggable="false">원본</a></div></div>
            </article>
            <p v-if="!found.length" class="none">맞는 뉴스가 없습니다.</p>
          </div>
        </template>

        <!-- 대시보드 -->
        <template v-else-if="tab === 'dash'">
          <div class="sech first"><h3>SK텔레콤 <small>017670 · 2021–2025 · DART 공시, 단위 억원</small></h3></div>
          <div class="dgrid">
            <section v-for="id in LAYOUT.dash" :key="id" class="dk" :class="span(id)" v-bind="pk(dashPick(id))">
              <span class="lk">대시보드</span>
              <header><b>{{ panelOf(id)!.title }}</b><span class="tag">{{ panelOf(id)!.sub }}</span></header>
              <DashPanel :panel="id" />
            </section>
          </div>
        </template>

        <!-- 회사 재무 -->
        <template v-else-if="tab === 'fin'">
          <div class="sech first"><h3>SK텔레콤 회사 재무 <small>DART 공시, 단위 억원</small></h3>
            <div class="seg" role="tablist" aria-label="재무 보기">
              <button v-for="(t, k) in LAYOUT.fin" :key="k" role="tab" :aria-selected="ftab === k" @click="ftab = String(k)">{{ t.label }}</button></div>
            <div v-if="LAYOUT.fin[ftab].q" class="seg" role="radiogroup" aria-label="기간">
              <button :aria-pressed="period === 'y'" @click="period = 'y'">연간</button><button :aria-pressed="period === 'q'" @click="period = 'q'">분기</button></div>
          </div>
          <div class="dgrid">
            <section v-for="id in finPanels" :key="id" class="dk" :class="span(id)" v-bind="pk(dashPick(id))">
              <span class="lk">대시보드</span>
              <header><b>{{ panelOf(id)!.title }}</b><span v-if="panelOf(id)!.sub" class="tag">{{ panelOf(id)!.sub }}</span></header>
              <DashPanel :panel="id" />
            </section>
          </div>
        </template>

        <!-- 인사이트: 3사 비교(분기 · 연간) + 관련 뉴스 -->
        <template v-else>
          <div class="sech first"><h3>3사 비교 인사이트 <small>1Q26 · {{ insights.length }}</small></h3><span class="tag ai">AI 해석</span></div>
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
          </div>
          <div class="sech"><h3>연간 인사이트 <small>2025 · {{ LAYOUT.insights.length }}</small></h3><span class="tag ai">AI 해석</span></div>
          <div class="dgrid">
            <section v-for="id in LAYOUT.insights" :key="id" class="dk icard2" :class="span(id)" v-bind="pk(dashPick(id))">
              <span class="lk">대시보드</span>
              <header><b>{{ panelOf(id)!.title }}</b></header>
              <div class="lab2">사실 <span class="tag">공시</span><span class="tag">계산</span><small>{{ panelOf(id)!.sub }}</small></div>
              <DashPanel :panel="id" />
            </section>
          </div>
          <div class="sech"><h3>관련 뉴스 <small>{{ news.length }}</small></h3>
            <div class="seg" role="radiogroup" aria-label="뉴스 회사"><button v-for="x in NF" :key="x" :aria-pressed="nf === x" @click="nf = x">{{ x }}</button></div></div>
          <div class="news">
            <article v-for="d in news" :key="d.id" class="ncard" v-bind="pk(docPick(d))">
              <span class="lk">뉴스</span>
              <div class="nimg"><img v-if="d.img" :src="d.img" alt="" loading="lazy" referrerpolicy="no-referrer" draggable="false" @error="noImg"><span>{{ d.publisher.slice(0, 1) }}</span></div>
              <div class="ntext"><div class="nm">{{ d.publisher }}<span class="dotsep">·</span>{{ date(d.date) }}</div>
                <div class="nt" :title="d.title">{{ d.title }}</div>
                <div class="nft"><span class="tag">{{ /3사|업계/.test(d.company) ? "3사 공통" : d.company }}</span><a v-if="d.url" :href="d.url" target="_blank" rel="noopener" draggable="false">원본</a></div></div>
            </article>
            <p v-if="!news.length" class="none">맞는 뉴스가 없습니다.</p>
          </div>
        </template>
      </div>
    </main>

    <!-- 하단: 탭(입력이 비었을 때) + 선택 칩 + [+ 입력창] [추가하기] -->
    <div class="dock">
      <div v-if="!sq" class="tabs3" role="tablist" aria-label="자료 검색 보기">
        <button v-for="[k, l] in TABS" :key="k" role="tab" :aria-selected="tab === k" @click="tab = k">{{ l }}</button>
      </div>
      <div v-if="sel.length" class="chips2" aria-label="선택한 항목">
        <span v-for="p in sel" :key="p.key" class="chip" :data-src="p.src"><b :title="p.label">{{ p.label }}</b>
          <button :aria-label="p.label + ' 선택 해제'" @click="toggle(p)">✕</button></span>
      </div>
      <div class="cinrow">
        <form class="cin" @submit.prevent="search">
          <button type="button" class="plus" :title="busy ? '요약하는 중' : '사내 자료 첨부'" aria-label="사내 자료 첨부" @click="file?.click()">+</button>
          <input ref="file" class="hide" type="file" multiple accept=".txt,.md,.csv,.json,.pdf,.xlsx,.docx,.hwp" @change="upload" />
          <input ref="input" v-model="q" type="search" :placeholder="PH" :aria-label="PH" autocomplete="off" />
        </form>
        <button type="button" class="addb" :disabled="!sel.length" @click="addAll">추가하기{{ sel.length ? ` ${sel.length}` : "" }}</button>
      </div>
      <small v-if="err" class="err">{{ err }}</small>
    </div>
    <SourceOverlay v-if="detail" :table-id="detail.table_id" :match="detail.hits.map((h) => h.r)" pick @close="detail = null" @add="picked" />
  </div>
</template>
