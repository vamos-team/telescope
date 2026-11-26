<script setup lang="ts">
// 캔버스 오른쪽 사이드바: 자료 검색 (하단 도구 "자료 추가", ⌘K)
// - 위 탭 3개: 회사 재무(주요 지표 · 영업이익 증감 요인 + 손익 · 재무상태 · 현금흐름 · 설비투자 · 동종사 비교) / DART(3사 산점도 · 현금 증감 · CapEx 비율 · 히트맵 · 자산 계정) / 인사이트(3사 비교 + 관련 뉴스)
// - 아래 고정 검색창: 입력하는 즉시 통합 검색(공시 재무 데이터 + IR·AR + 뉴스, 실제 서비스는 임베딩 검색 자리). Esc = 검색 지우고 탭 화면으로
// - 항목은 끌어서 캔버스에 놓음 (두 번 누르면 캔버스 가운데에 올림). 선택 목록 · 추가하기 없음
// - 검색창 왼쪽 +: 데이터 입력 창 (포맷 · 회사 검증 뒤 사내 자료 카드로)
import { ref, watch, computed, nextTick } from "vue";
import { api, docTitle, KIND_LABEL, type SearchResult, type Doc, type Insight } from "../api";
import { useCanvas } from "../stores/canvas";
import { LAYOUT, panelOf } from "../local/dashboard";
import DashPanel from "./DashPanel.vue";
import SourceOverlay from "./SourceOverlay.vue";
import DataInputDialog from "./DataInputDialog.vue";

// 캔버스로 보내는 항목. 끌기 데이터(application/x-canvas)와 "추가하기"가 같은 모양을 씀
export interface Pick { key: string; label: string; tag: string; src: string; kind: "doc" | "table" | "compare" | "dash";
  id?: string; tableId?: string; rows?: number[]; cols?: number[]; tables?: string[]; panel?: string }

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: []; add: [items: Pick[]]; example: [] }>();
defineExpose({ focusSearch: () => nextTick(() => input.value?.focus()) });
const cv = useCanvas();
const A = api.analysis;
const TABS = [["fin", "회사 재무"], ["dash", "DART"], ["insight", "인사이트"]] as const;
type Tab = typeof TABS[number][0];
const tab = ref<Tab>("fin"), ftab = ref("pl"), period = ref<"y" | "q">("y");
const q = ref(""), sq = ref(""), input = ref<HTMLInputElement>(), inputOpen = ref(false);
const dart = ref<SearchResult[]>([]), irar = ref<Doc[]>([]), found = ref<Doc[]>([]);
const basis = ref<{ companies: string[]; tags: string[] }>({ companies: [], tags: [] });
const nf = ref("전체");

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
watch([() => props.open, () => cv.cards.length], async ([open]) => { if (open) await refreshBasis(); }, { immediate: true });
// Esc: 검색 중이면 지우고 탭 화면으로 (캔버스 Esc 처리로 넘기지 않음)
// 검색창에서 Tab · Shift+Tab: 회사 재무 → DART → 인사이트 탭 순환 (검색어가 있으면 검색 결과를 그대로 두고 넘어가지 않음)
function cycleTab(e: KeyboardEvent) {
  if (e.isComposing || e.altKey || e.metaKey || e.ctrlKey) return;
  e.preventDefault();
  if (q.value) return;
  const i = TABS.findIndex(([k]) => k === tab.value);
  tab.value = TABS[(i + (e.shiftKey ? TABS.length - 1 : 1)) % TABS.length][0];
}
function esc(e: KeyboardEvent) { if (q.value) { e.stopPropagation(); q.value = ""; sq.value = ""; } else (e.target as HTMLElement).blur(); }

// 인사이트 탭: 관련 뉴스는 캔버스 회사 · 개념과 겹치는 순, 회사 필터
const NF = ["전체", "SKT", "KT", "LGU+", "3사 공통"];
const coOk = (co: string) => nf.value === "전체" || (nf.value === "3사 공통" ? /3사|업계/.test(co) : co === nf.value);
const news = computed(() => api.recommend(basis.value.companies, basis.value.tags, ["news"]).map((x) => x.doc).filter((d) => coOk(d.company)));
// 3사 비교 인사이트(분기, 공시 표 → 비교 카드): 캔버스 개념과 겹치는 수로 정렬
const insights = computed(() => A.insights.map((i) => ({ i, s: i.concepts.filter((c) => basis.value.tags.includes(c)).length })).sort((a, b) => b.s - a.s).map((x) => x.i));
const facts = (i: Insight) => api.peerFacts(i.concepts.slice(0, 2));
const factsOf = ref<Insight | null>(null);  // 표 보기 팝업에 띄운 3사 비교 인사이트
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

// 재무 데이터 행 → 원본 창에서 행 고르기 → 캔버스에 바로 올림
const detail = ref<SearchResult | null>(null);
function picked(_: string, rows: number[], cols: number[]) { emit("add", [tablePick(detail.value!, rows, cols)]); detail.value = null; }

// ---------- 끌어서 캔버스에 놓기 (두 번 누르면 가운데에 올림). 차트 안 조작 버튼(.nodrag)은 끌기 · 올리기 아님 ----------
const inner = (e: Event) => !!(e.target as HTMLElement).closest("a,button,input,label,select,.nodrag");
function dragStart(p: Pick, e: DragEvent) {
  if (cv.readOnly || inner(e)) { e.preventDefault(); return; }
  e.dataTransfer!.setData("application/x-canvas", JSON.stringify([p]));
  e.dataTransfer!.effectAllowed = "copy";
}
const pk = (p: Pick) => ({ class: "pickable", draggable: !cv.readOnly, "data-src": p.src, title: "캔버스로 끌어 놓기 (두 번 누르면 가운데에 올림)",
  onDragstart: (e: DragEvent) => dragStart(p, e), onDblclick: (e: MouseEvent) => { if (!inner(e) && !cv.readOnly) emit("add", [p]); } });
const noImg = (e: Event) => { const el = e.target as HTMLImageElement; el.parentElement?.classList.add("noimg"); el.remove(); };

const PH = "공시 · IR · AR · 뉴스 통합 검색 (예: SKT 감가상각비)";
</script>
<template>
  <aside v-if="open" class="adder spanel" aria-label="자료 검색">
    <header class="sph">
      <button class="pclose" data-tip="사이드바 접기" aria-label="오른쪽 사이드바 접기" @click="emit('close')"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M12.5 3.5v13" /></svg></button>
      <div v-if="!sq" class="tabs3" role="tablist" aria-label="자료 검색 보기">
        <button v-for="[k, l] in TABS" :key="k" role="tab" :aria-selected="tab === k" @click="tab = k">{{ l }}</button>
      </div>
      <b v-else class="sqt">검색 결과 <small>Esc로 지우기</small></b>
      <button v-if="api.meta.example && cv.canEdit" class="ex" @click="emit('example')">예시 불러오기</button>
    </header>

    <main class="stage">
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

        <!-- DART -->
        <template v-else-if="tab === 'dash'">
          <div class="sech first"><h3>DART <small>SK텔레콤 017670 · 2021–2025 · DART 공시, 단위 억원</small></h3></div>
          <div class="dgrid dart">
            <section v-for="id in LAYOUT.dash" :key="id" class="dk" :class="span(id)" v-bind="pk(dashPick(id))">
              <span class="lk">대시보드</span>
              <header><b>{{ panelOf(id)!.title }}</b><span class="tag">{{ panelOf(id)!.sub }}</span></header>
              <DashPanel :panel="id" />
            </section>
          </div>
        </template>

        <!-- 회사 재무 -->
        <template v-else-if="tab === 'fin'">
          <div class="sech first"><h3>SK텔레콤 회사 재무 <small>017670 · 2021–2025 · DART 공시, 단위 억원</small></h3></div>
          <div class="dgrid">
            <section v-for="id in LAYOUT.finTop" :key="id" class="dk" :class="span(id)" v-bind="pk(dashPick(id))">
              <span class="lk">대시보드</span>
              <header><b>{{ panelOf(id)!.title }}</b><span class="tag">{{ panelOf(id)!.sub }}</span></header>
              <DashPanel :panel="id" />
            </section>
          </div>
          <div class="sech">
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
              <!-- 사실 표는 접어 두고, 표 보기를 누르면 팝업 -->
              <div class="lab2">사실 <span class="tag">공시</span><span class="tag">계산</span><small>{{ facts(i).facts.map((f) => f.name).join(" · ") }}</small>
                <button class="tview nodrag" @click.stop="factsOf = i">표 보기</button></div>
              <div class="aip"><div class="ai-lab">AI 해석</div><p :title="i.ai">{{ i.ai }}</p>
                <div class="nrefs"><template v-for="n in i.news.slice(0, 2)" :key="n"><a v-if="api.docs.get(n)" :href="api.docs.get(n)!.url" target="_blank" rel="noopener" draggable="false">
                  {{ api.docs.get(n)!.publisher }} {{ api.docs.get(n)!.date.slice(5) }} · {{ api.docs.get(n)!.title }}</a></template></div></div>
            </section>
          </div>
          <div class="sech"><h3>연간 인사이트 <small>2025 · {{ LAYOUT.insights.length }}</small></h3><span class="tag ai">AI 해석</span></div>
          <div class="dgrid hrow">
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

    <!-- 아래 고정: [+ | 통합 검색] -->
    <div class="dock">
      <form class="cin" @submit.prevent="search">
        <button v-if="cv.canEdit" type="button" class="plus" title="데이터 입력 (사내 자료)" aria-label="데이터 입력" @click="inputOpen = true">+</button>
        <input ref="input" v-model="q" type="search" :placeholder="PH" :aria-label="PH" autocomplete="off" @keydown.esc="esc" @keydown.tab="cycleTab" />
      </form>
    </div>
    <SourceOverlay v-if="detail" :table-id="detail.table_id" :match="detail.hits.map((h) => h.r)" pick @close="detail = null" @add="picked" />
    <!-- 3사 비교 사실 표 팝업 (화면 가운데) -->
    <div v-if="factsOf" class="mdim" @click.self="factsOf = null">
      <section class="modal sm ftpop" role="dialog" aria-modal="true" :aria-label="factsOf.title">
        <div class="mh"><h3>{{ factsOf.title }}</h3><button class="btn grey sm" @click="factsOf = null">닫기</button></div>
        <div class="lab2">사실 <span class="tag">공시</span><span class="tag">계산</span><small>십억원 · 1Q25 대비 1Q26</small></div>
        <table v-for="f in facts(factsOf).facts" :key="f.concept" class="g facts">
          <thead><tr><th>{{ f.name }}</th><th class="n">1Q25</th><th class="n">1Q26</th><th class="n">증감</th></tr></thead>
          <tbody><tr v-for="r in f.rows" :key="r.company" :class="{ b: basis.companies.includes(r.company) }">
            <td>{{ r.company }}</td><td class="n">{{ num(r.prv) }}</td><td class="n">{{ num(r.cur) }}</td><td class="n">{{ pct(r.prv, r.cur) }}</td></tr></tbody>
        </table>
      </section>
    </div>
    <DataInputDialog v-if="inputOpen" @close="inputOpen = false" @add="(ps) => { inputOpen = false; emit('add', ps); }" />
  </aside>
</template>
