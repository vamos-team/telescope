<script setup lang="ts">
// 자료 추가 화면 (+ 자료 추가, ⌘K): 캔버스 위를 어둡게 덮고 카드를 띄운다. 하단 가운데 탭(공시 / IR·뉴스 / 사내 자료)과 채팅창형 검색창(v01 v04 하단 입력창 모양)
// 탭 전환: ⌘/Ctrl+1·2·3 (Tab은 기본 포커스 이동). 닫기: Esc, 어두운 화면이나 오른쪽 위 ✕
// 카드를 누르면 선택되고, 선택이 있으면 어두운 화면이 걷혀 선택한 카드를 캔버스로 끌어다 놓는 화면이 된다
import { ref, watch, computed, nextTick, onMounted, onUnmounted } from "vue";
import { api, docTitle, type SearchResult, type Doc, type Insight } from "../api";
import { useCanvas } from "../stores/canvas";

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ close: []; pick: [tableId: string, rows: number[]]; addTable: [tableId: string, rows: number[]];
  addDoc: [id: string]; addCompare: [tableIds: string[]]; example: [] }>();
const cv = useCanvas();
const A = api.analysis;
const tab = ref<"dart" | "news" | "internal">("dart");
const q = ref(""), input = ref<HTMLInputElement>();
const dart = ref<SearchResult[]>([]), docs = ref<{ doc: Doc; why: string; s?: number }[]>([]);
const bump = ref(0);  // 업로드 뒤 목록 다시 읽기
const busy = ref(false), err = ref("");
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

let t = 0;
async function load() {
  if (tab.value === "dart") dart.value = q.value.trim() ? await api.searchQuestion(q.value) : [];
  else if (tab.value === "internal") docs.value = await api.searchDocs(q.value, ["internal"]);
  else docs.value = q.value.trim() ? await api.searchDocs(q.value, ["ir", "news"]) : api.recommend(basis.value.companies, basis.value.tags, ["ir", "news"]);
}
watch([q, bump], () => { clearTimeout(t); t = window.setTimeout(load, 120); });
// 탭을 바꾸거나 팝업을 열 때마다 추천 기준을 다시 계산
watch([() => props.open, tab], async ([open], old) => {
  if (!open) return;
  if (!old?.[0]) { q.value = ""; tab.value = "dart"; sel.value = []; browse.value = false; dragging.value = false; }  // 새로 열면 공시 탭부터
  await refreshBasis(); await load();
  nextTick(() => input.value?.focus());
}, { immediate: true });

const onCanvas = (id: string) => cv.cards.some((c) => c.kind === "doc" && c.docId === id);
// 화면 맨 위 가운데 회사 필터: IR 자료와 뉴스 모두에 적용
const coOk = (co: string) => nf.value === "전체" || (nf.value === "3사 공통" ? /3사|업계/.test(co) : co === nf.value);
const ir = computed(() => docs.value.filter((x) => x.doc.kind === "ir" && coOk(x.doc.company)));
const NF = ["전체", "SKT", "KT", "LGU+", "3사 공통"];
const news = computed(() => docs.value.filter((x) => x.doc.kind === "news" && coOk(x.doc.company)));

// 3사 비교 AI 분석: 캔버스 개념과 겹치는 수 + 지금 목록(추천·질문 결과)에 있는 근거 뉴스 수로 정렬. 질문 중이면 닿는 것만
// 3사 비교 AI 분석 (공시 탭): 캔버스 개념과 겹치는 수로 정렬. 질문 중이면 질문 개념이 닿는 분석만 (개념이 안 잡히면 찾은 공시 표로)
const insights = computed(() => {
  const found = new Set(dart.value.map((r) => r.table_id)), qc = api.conceptsFor(q.value);
  const scored = A.insights.map((i) => ({ i, s: i.concepts.filter((c) => basis.value.tags.includes(c)).length,
    hit: qc.size ? i.concepts.some((c) => qc.has(c)) : facts(i).tables.some((t) => found.has(t)) }));
  return (q.value.trim() ? scored.filter((x) => x.hit) : scored).sort((a, b) => b.s - a.s).map((x) => x.i);
});
const facts = (i: Insight) => api.peerFacts(i.concepts.slice(0, 2));
const pct = (a: number | null, b: number | null) => a == null || b == null || !a ? "—" : `${b >= a ? "+" : ""}${((b - a) / Math.abs(a) * 100).toFixed(1)}%`;
const num = (v: number | null) => v == null ? "—" : v.toLocaleString("ko-KR", { minimumFractionDigits: 1, maximumFractionDigits: 1 });

// 업로드: 실제 서비스는 파서 모델이 본문·표를 요약하고 임베딩해 적재. 데모는 텍스트 파일만 읽어 앞부분을 요약으로 쓴다
const KEYWORDS: Record<string, string[]> = { capex: ["capex", "설비투자", "설비"], ai_dc: ["데이터센터", "aidc", "gpu"], da: ["감가상각"],
  comm: ["수수료", "마케팅"], rev: ["매출", "영업수익"], opex: ["영업비용", "비용"], opi: ["영업이익"], dividend: ["배당"], labor: ["인건비"], debt: ["차입"],
  rp_purch: ["특수관계자", "내부거래", "계열사"] };
async function upload(e: Event) {
  await uploadFiles(Array.from((e.target as HTMLInputElement).files ?? []));
  (e.target as HTMLInputElement).value = "";
}
const over = ref(false);  // 파일을 올리기 칸 위로 끄는 중
function dropFiles(e: DragEvent) { over.value = false; const fs = Array.from(e.dataTransfer?.files ?? []); if (fs.length) uploadFiles(fs); }
async function uploadFiles(files: File[]) {
  busy.value = true; err.value = "";
  try {
    for (const [i, f] of files.entries()) {
      const isText = /\.(txt|md|csv|json)$/i.test(f.name);
      const text = isText ? (await f.text()).replace(/\s+/g, " ").trim() : "";
      const figures = [...text.matchAll(/([가-힣A-Za-z]{2,12})\s*[:=]?\s*(-?\d[\d,]*(?:\.\d+)?)\s*(십억원|억원|백만원|조원|원|%)/g)].slice(0, 4)
        .map((m) => ({ label: m[1], value: +m[2].replace(/,/g, ""), unit: m[3], period: "업로드 자료" }));
      const hay = (f.name + text).toLowerCase().replace(/\s+/g, "");
      api.registerDoc({ id: `up${Date.now()}${i}`, kind: "internal", company: A.focus, title: f.name.replace(/\.[^.]+$/, ""),
        publisher: "업로드 (파서 요약)", date: new Date().toISOString().slice(0, 10), url: "", uploaded: true, figures,
        tags: Object.entries(KEYWORDS).filter(([, ks]) => ks.some((k) => hay.includes(k))).map(([id]) => id),
        summary: text ? text.slice(0, 180) + (text.length > 180 ? "…" : "")
          : `${f.name.split(".").pop()!.toUpperCase()} ${Math.ceil(f.size / 1024)}KB. 파서 모델을 연결하면 본문 요약과 표 수치가 이 자리에 들어감` });
    }
    q.value = ""; bump.value++;
  } catch (x) { err.value = `파일을 읽지 못했습니다: ${(x as Error).message}`; }
  finally { busy.value = false; }
}

const PH = { dart: "공시 데이터를 검색하세요 (예: SKT 감가상각비, KT 영업이익)",
  news: "IR 자료와 뉴스를 검색하세요 (예: SKT 설비투자, 데이터센터 전력)", internal: "사내 자료를 검색하세요 (예: 설비투자 예산안, 회선 증설)" };
const KIND = { ir: "IR", news: "뉴스", internal: "사내" };



// ---------- 선택 → 끌어다 놓기 ----------
// 끌기 데이터: application/x-canvas = 선택한 항목 배열 [{kind:"doc",id} | {kind:"table",tableId,rows} | {kind:"compare",tables}]
interface Pick { key: string; label: string; tag: string; kind: "doc" | "table" | "compare"; id?: string; tableId?: string; rows?: number[]; tables?: string[] }
const sel = ref<Pick[]>([]);
const browse = ref(false);    // 선택이 있어도 카드를 더 고르는 중
const dragging = ref(false);  // 카드를 바로 끄는 중
// 누르고 있는 카드: 캔버스 카드 모양(데이터 성격별 색 + 종류 표시)으로 바뀜. 끌기 미리보기 이미지도 이 모양으로 찍힘
const hold = ref("");
const mode = computed(() => dragging.value || (sel.value.length && !browse.value) ? "drag" : "dim");
const isSel = (k: string) => sel.value.some((x) => x.key === k);
// 누르면 선택하고 바로 끌어다 놓는 화면으로. Shift·⌘/Ctrl을 누른 채 누르면 어두운 화면에 남아 여러 장을 고름
function toggle(p: Pick, e?: MouseEvent) {
  sel.value = isSel(p.key) ? sel.value.filter((x) => x.key !== p.key) : [...sel.value, p];
  browse.value = !!(e && (e.shiftKey || e.metaKey || e.ctrlKey));
}
const docPick = (d: Doc): Pick => ({ key: "doc:" + d.id, label: docTitle(d), tag: KIND[d.kind], kind: "doc", id: d.id });
const tablePick = (r: SearchResult): Pick => ({ key: "tbl:" + r.table_id, label: `${r.company} ${r.title}`, tag: "공시", kind: "table", tableId: r.table_id, rows: r.hits.map((h) => h.r) });
const insPick = (i: Insight): Pick => ({ key: "ins:" + i.id, label: i.title, tag: "3사 비교", kind: "compare", tables: facts(i).tables });
function startDrag(e: DragEvent, p?: Pick) {
  if (p && !isSel(p.key)) sel.value = [...sel.value, p];
  e.dataTransfer!.setData("application/x-canvas", JSON.stringify(sel.value));
  e.dataTransfer!.effectAllowed = "copy";
  setTimeout(() => { dragging.value = true; hold.value = ""; });  // 같은 틱에 화면을 바꾸면 브라우저가 끌기를 취소함
}

// ---------- 키보드: ⌘/Ctrl+1·2·3 로 탭 전환. Tab은 막지 않아 키보드로 버튼·업로드까지 이동 (Esc 닫기는 App) ----------
const TABS = [["dart", "공시"], ["news", "IR·뉴스"], ["internal", "사내 자료"]] as const;
function onKey(e: KeyboardEvent) {
  if (!props.open) return;
  if ((e.metaKey || e.ctrlKey) && ["1", "2", "3"].includes(e.key)) { e.preventDefault(); tab.value = TABS[+e.key - 1][0]; browse.value = true; }
}
onMounted(() => window.addEventListener("keydown", onKey));
onUnmounted(() => window.removeEventListener("keydown", onKey));
const mac = /Mac|iPhone|iPad/.test(navigator.platform);
</script>

<template>
  <div v-if="open" class="adder" :class="[mode, 'tab-' + tab, { over }]"
    @dragover="tab === 'internal' && $event.dataTransfer?.types.includes('Files') && ($event.preventDefault(), over = true)"
    @dragleave.self="over = false" @drop="tab === 'internal' && $event.dataTransfer?.types.includes('Files') && ($event.preventDefault(), dropFiles($event))" role="dialog" aria-label="자료 추가">
    <div v-if="mode === 'dim'" class="dimbg" />
    <div class="corner">
      <button v-if="api.meta.example" class="ex" @click="emit('example')">예시 불러오기</button>
      <button class="x" aria-label="닫기 (Esc)" title="닫기 (Esc)" @click="emit('close')">✕</button>
    </div>

    <div v-if="tab === 'news' && mode === 'dim'" class="cofilter seg" role="radiogroup" aria-label="회사">
      <button v-for="x in NF" :key="x" :aria-pressed="nf === x" @click="nf = x">{{ x }}</button></div>

    <!-- 흐린 화면 위 카드 -->
    <main class="stage" :aria-hidden="mode === 'drag'">
      <div class="inner">
        <!-- 공시: 3사 비교 AI 분석 + 찾은 공시 표 -->
        <template v-if="tab === 'dart'">
          <div class="icards row">
            <section v-for="i in insights" :key="i.id" class="icard pickable" :class="{ on: isSel('ins:' + i.id), lift: hold === 'ins:' + i.id }" @pointerdown="hold = 'ins:' + i.id" @pointerup="hold = ''" @pointerleave="hold = ''" draggable="true"
              @click="toggle(insPick(i), $event)" @dragstart="startDrag($event, insPick(i))" @dragend="dragging = false">
              <span class="lk co">비교</span>
              <h4>{{ i.title }}</h4>
              <div>
                <div class="lab2">사실 <span class="tag">공시</span><span class="tag">계산</span><small>십억원 · 각 사 공시 표에서 계산</small></div>
                <table v-for="f in facts(i).facts" :key="f.concept" class="g facts">
                  <thead><tr><th>{{ f.name }}</th><th class="n">1Q25</th><th class="n">1Q26</th><th class="n">증감</th></tr></thead>
                  <tbody><tr v-for="r in f.rows" :key="r.company" :class="{ b: basis.companies.includes(r.company) }">
                    <td>{{ r.company }}</td><td class="n">{{ num(r.prv) }}</td><td class="n">{{ num(r.cur) }}</td><td class="n">{{ pct(r.prv, r.cur) }}</td></tr></tbody>
                </table>
              </div>
              <div class="aip"><div class="ai-lab">AI 해석</div><p :title="i.ai">{{ i.ai }}</p>
                <div class="nrefs"><template v-for="n in i.news.slice(0, 2)" :key="n"><a v-if="api.docs.get(n)" :href="api.docs.get(n)!.url" target="_blank" rel="noopener" draggable="false" @click.stop>
                  {{ api.docs.get(n)!.publisher }} {{ api.docs.get(n)!.date.slice(5) }} · {{ api.docs.get(n)!.title }}</a></template></div></div>
              <button class="ask2" @click.stop="emit('addCompare', facts(i).tables)">비교 카드로 올리기 · 3사 표 {{ facts(i).tables.length }}개</button>
            </section>
            <p v-if="!insights.length" class="none">검색어와 닿는 비교 분석이 없습니다.</p>
          </div>
          <template v-if="q.trim()">
            <div class="sech"><h3>공시 표 <small>{{ dart.length }}</small></h3></div>
            <div class="tgrid">
              <article v-for="r in dart" :key="r.table_id" class="tcard pickable" :data-co="r.company" :class="{ on: isSel('tbl:' + r.table_id), lift: hold === 'tbl:' + r.table_id }" @pointerdown="hold = 'tbl:' + r.table_id" @pointerup="hold = ''" @pointerleave="hold = ''" draggable="true"
                @click="toggle(tablePick(r), $event)" @dragstart="startDrag($event, tablePick(r))" @dragend="dragging = false">
                <span class="lk co" :data-co="r.company">{{ r.company }}</span>
                <b class="ti">{{ r.title }}</b>
                <small>{{ [r.basis, r.period].filter(Boolean).join(" · ") }}</small>
                <span class="m"><span v-for="h in r.hits.slice(0, 4)" :key="h.r">{{ h.label }}<em>{{ h.why }}</em></span></span>
                <div class="ft"><span class="meta">{{ r.company }} · {{ r.unit }}</span>
                  <button class="line" @click.stop="emit('pick', r.table_id, r.hits.map((h) => h.r))">행 고르기</button>
                  <button @click.stop="emit('addTable', r.table_id, r.hits.map((h) => h.r))">+ 찾은 행 올리기</button></div>
              </article>
              <p v-if="!dart.length" class="none">일치하는 계정이 없습니다. 다른 표현으로 검색해 보세요.</p>
            </div>
          </template>
        </template>

        <!-- IR·뉴스 -->
        <template v-else-if="tab === 'news'">
          <div class="sech"><h3>IR 자료 원본 <small>{{ ir.length }}</small></h3></div>
          <div class="hs">
            <article v-for="{ doc: d, why } in ir" :key="d.id" class="dcard pickable" :class="{ on: isSel('doc:' + d.id), lift: hold === 'doc:' + d.id }" @pointerdown="hold = 'doc:' + d.id" @pointerup="hold = ''" @pointerleave="hold = ''" :data-kind="d.kind" draggable="true"
              @click="toggle(docPick(d), $event)" @dragstart="startDrag($event, docPick(d))" @dragend="dragging = false">
              <span class="lk kind" :data-kind="d.kind">{{ KIND[d.kind] }}</span>
              <b class="ti" :title="d.title">{{ docTitle(d) }}</b>
              <p class="su">{{ d.summary }}</p>
              <div class="fg"><span v-for="(f, i) in d.figures.slice(0, 2)" :key="i">{{ f.label }} <b>{{ f.value.toLocaleString("ko-KR") }}{{ f.unit === "%" ? "%" : " " + f.unit }}</b></span></div>
              <small class="why">{{ why }}</small>
              <div class="ft"><span class="meta">{{ d.company }} · {{ d.publisher }} · {{ d.date.slice(2).replace(/-/g, ".") }}</span><a v-if="d.url" :href="d.url" target="_blank" rel="noopener" draggable="false" @click.stop>원본</a>
                <button :disabled="onCanvas(d.id)" @click.stop="emit('addDoc', d.id)">{{ onCanvas(d.id) ? "올림" : "+ 올리기" }}</button></div>
            </article>
            <p v-if="!ir.length" class="none">맞는 IR 자료가 없습니다.</p>
          </div>
          <div class="sech"><h3>관련 뉴스 <small>{{ news.length }}</small></h3></div>
          <div class="hs">
            <article v-for="{ doc: d, why } in news" :key="d.id" class="dcard pickable" :class="{ on: isSel('doc:' + d.id), lift: hold === 'doc:' + d.id }" @pointerdown="hold = 'doc:' + d.id" @pointerup="hold = ''" @pointerleave="hold = ''" :data-kind="d.kind" draggable="true"
              @click="toggle(docPick(d), $event)" @dragstart="startDrag($event, docPick(d))" @dragend="dragging = false">
              <span class="lk kind" :data-kind="d.kind">{{ KIND[d.kind] }}</span>
              <b class="ti" :title="d.title">{{ docTitle(d) }}</b>
              <p class="su">{{ d.summary }}</p>
              <div class="fg"><span v-for="(f, i) in d.figures.slice(0, 2)" :key="i">{{ f.label }} <b>{{ f.value.toLocaleString("ko-KR") }}{{ f.unit === "%" ? "%" : " " + f.unit }}</b></span></div>
              <small class="why">{{ why }}</small>
              <div class="ft"><span class="meta">{{ d.company }} · {{ d.publisher }} · {{ d.date.slice(2).replace(/-/g, ".") }}</span><a v-if="d.url" :href="d.url" target="_blank" rel="noopener" draggable="false" @click.stop>원본</a>
                <button :disabled="onCanvas(d.id)" @click.stop="emit('addDoc', d.id)">{{ onCanvas(d.id) ? "올림" : "+ 올리기" }}</button></div>
            </article>
            <p v-if="!news.length" class="none">맞는 뉴스가 없습니다.</p>
          </div>
        </template>

        <!-- 사내 자료 -->
        <template v-else>
          <!-- 사내 자료는 리스트: 제목 · 요약 한 줄 · 핵심 수치 · 출처 · 올리기 -->
          <div class="ilist">
            <article v-for="{ doc: d, why } in docs" :key="d.id" class="irow pickable" :class="{ on: isSel('doc:' + d.id), lift: hold === 'doc:' + d.id }" data-kind="internal" draggable="true"
              @pointerdown="hold = 'doc:' + d.id" @pointerup="hold = ''" @pointerleave="hold = ''"
              @click="toggle(docPick(d), $event)" @dragstart="startDrag($event, docPick(d))" @dragend="dragging = false">
              <span class="lk kind" data-kind="internal">사내</span>
              <div class="tx"><b :title="d.title">{{ d.title }}</b><span>{{ d.summary }}</span></div>
              <div class="fg"><span v-for="(f, i) in d.figures.slice(0, 2)" :key="i">{{ f.label }} <b>{{ f.value.toLocaleString("ko-KR") }} {{ f.unit }}</b></span></div>
              <span class="meta" :title="why">{{ d.publisher }} · {{ d.date.slice(2).replace(/-/g, ".") }}</span>
              <button :disabled="onCanvas(d.id)" @click.stop="emit('addDoc', d.id)">{{ onCanvas(d.id) ? "올림" : "+ 올리기" }}</button>
            </article>
            <p v-if="!docs.length" class="none">일치하는 자료가 없습니다.</p>
          </div>
          <!-- 리스트 아래 남는 공간의 세로 가운데: 누르면 파일 선택, 파일은 화면 어디에 끌어다 놓아도 올라감 -->
          <div class="upwrap">
            <label class="upline">
              <input type="file" multiple accept=".txt,.md,.csv,.json,.pdf,.xlsx,.docx,.hwp" @change="upload" />
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 13V3.5M6 7.5l4-4 4 4" /><path d="M3.5 12.5v3a1.5 1.5 0 0 0 1.5 1.5h10a1.5 1.5 0 0 0 1.5-1.5v-3" /></svg>
              <span>{{ busy ? "요약하는 중" : over ? "놓으면 올라갑니다" : "클릭하거나 파일을 끌어 놓으세요" }}</span>
              <small>TXT, MD, CSV (PDF·XLSX·DOCX·HWP는 파서 연결 후)</small>
              <small v-if="err" class="err">{{ err }}</small>
            </label>
          </div>
        </template>
      </div>
    </main>

    <!-- 선택한 카드: 어두운 화면이 걷히고 캔버스로 끌어다 놓는 단계 -->
    <section v-if="mode === 'drag' && sel.length" class="tray" aria-label="선택한 카드">
      <header><b>선택한 {{ sel.length }}개를 캔버스로 끌어다 놓으세요</b>
        <button @click="browse = true">카드 더 고르기</button><button @click="sel = []">선택 해제</button></header>
      <div class="items" draggable="true" @dragstart="startDrag($event)" @dragend="dragging = false">
        <span v-for="p in sel" :key="p.key" class="pk"><span class="kind">{{ p.tag }}</span><b>{{ p.label }}</b>
          <button :aria-label="p.label + ' 선택 해제'" @click="toggle(p)">✕</button></span>
      </div>
    </section>

    <!-- 하단 가운데: 탭 버튼 + 채팅창형 검색창. 입력하면 바로 결과가 바뀜(버튼 없음) -->
    <div class="dock">
      <div class="tabs3" role="tablist" aria-label="자료 종류">
        <button v-for="[k, label] in TABS" :key="k" role="tab" :aria-selected="tab === k" :data-tab="k" @click="tab = k; browse = true">{{ label }}</button>
      </div>
      <form class="cin" @submit.prevent="load">
        <input ref="input" v-model="q" type="search" :placeholder="PH[tab]" :aria-label="PH[tab]" autocomplete="off" @focus="browse = true" />
      </form>
      <small class="keys">{{ mac ? "⌘" : "Ctrl+" }}1~3 탭 전환 · Esc 닫기</small>
    </div>
  </div>
</template>
