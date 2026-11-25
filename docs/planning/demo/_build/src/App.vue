<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from "vue";
import { VueFlow, Panel, MarkerType, useVueFlow, type Node, type Edge, type NodeDragEvent, type EdgeMouseEvent } from "@vue-flow/core";
import SourceModal from "./components/SourceModal.vue";
import ResultPanel from "./components/ResultPanel.vue";
import SourceOverlay from "./components/SourceOverlay.vue";
import TableNode from "./components/nodes/TableNode.vue";
import CompareNode from "./components/nodes/CompareNode.vue";
import DocNode from "./components/nodes/DocNode.vue";
import MemoNode from "./components/nodes/MemoNode.vue";
import ChartNode from "./components/nodes/ChartNode.vue";
import LoginView from "./components/LoginView.vue";
import LeftPanel from "./components/LeftPanel.vue";
import Logo from "./components/Logo.vue";
import CanvasTabs from "./components/CanvasTabs.vue";
import { useCanvas, RELS, who, type TableCard } from "./stores/canvas";
import { api, docTitle } from "./api";
import { selfCheck } from "./local/ai";

const cv = useCanvas();
const flow = useVueFlow("canvas");
const srcOpen = ref(false);  // 자료 추가 팝업
const rp = ref("context");  // 오른쪽 패널 탭
const lpOpen = ref(true), rpOpen = ref(true);  // 왼쪽·오른쪽 사이드바 열림 (기본 둘 다 열림)
const selLink = ref<string | null>(null);

// 캔버스 상태 → Vue Flow 노드/엣지 (좌표는 store가 원본)
const nodes = computed<Node[]>(() => cv.cards.map((c) => ({ id: c.id, type: c.kind, position: { x: c.x, y: c.y },
  data: { cardId: c.id }, dragHandle: ".drag-handle" })));
const REL_COLOR: Record<string, string> = { 근거: "var(--green)", 설명: "var(--primary-text)", 반박: "var(--red)", 관련: "var(--t-cap)" };
const name = (t: string) => api.analysis.tag_names[t] ?? t;
const edges = computed<Edge[]>(() => {
  const out: Edge[] = cv.cards.flatMap((c) => c.kind === "compare"
    ? c.sources.map((s) => ({ id: `${s}-${c.id}`, source: s, target: c.id, animated: true, selectable: false }))
    : c.kind === "chart" ? [{ id: `${c.compareId}-${c.id}`, source: c.compareId, target: c.id, animated: true, selectable: false }] : []);
  for (const l of cv.links) out.push({ id: l.id, source: l.source, target: l.target, label: l.rel, markerEnd: MarkerType.ArrowClosed,
    class: { sel: selLink.value === l.id }, style: { stroke: REL_COLOR[l.rel], strokeWidth: 2, opacity: 1 }, labelBgPadding: [6, 3], labelBgBorderRadius: 6 });
  // 온톨로지 자동 연결: 문서 카드와 공시 표 카드가 같은 개념(태그)을 가지면 점선으로 잇는다
  const linked = new Set(cv.links.flatMap((l) => [l.source + l.target, l.target + l.source]));
  for (const d of cv.cards.filter((c) => c.kind === "doc")) for (const t of cv.cards.filter((c) => c.kind === "table")) {
    const shared = (cv.tags[d.id] ?? []).filter((x) => (cv.tags[t.id] ?? []).includes(x));
    if (shared.length && !linked.has(d.id + t.id)) out.push({ id: `auto-${d.id}-${t.id}`, source: d.id, target: t.id, selectable: false,
      label: "같은 개념: " + shared.map(name).join(", "), class: "auto" });
  }
  return out;
});
const onDragStop = (e: NodeDragEvent) => cv.move(e.node.id, e.node.position.x, e.node.position.y);
flow.onConnect((c) => cv.addLink(c.source, c.target));
const onEdgeClick = ({ edge }: EdgeMouseEvent) => { if (cv.links.some((l) => l.id === edge.id)) selLink.value = edge.id; };
const link = computed(() => cv.links.find((l) => l.id === selLink.value) ?? null);
const cardName = (id: string) => { const c = cv.cards.find((x) => x.id === id); const d = c?.kind === "doc" ? api.docs.get(c.docId) : undefined; return d ? docTitle(d) : id; };

function onPick(tableId: string, rows: number[]) { srcOpen.value = false; cv.openSource(tableId, rows); }
async function addFound(tableId: string, rows: number[], pos = nextPos()) {
  const t = await api.table(tableId), cols = t.cols.map((_, i) => i);
  cv.addTableCard(tableId, rows.length ? rows : t.rows.map((r) => r.r), cols, cols[0], pos);
}
// 인사이트의 "비교 카드로 올리기": 3사 표를 올려 비교 카드로 묶는다. 값 열은 1Q26(당분기)
const CUR = /1Q26|당분기|제 10 기 1분기/;
async function addCompare(tableIds: string[], at?: { x: number; y: number }) {
  srcOpen.value = false;
  for (const c of cv.cards) if (c.kind === "table") c.checked = false;
  for (const [k, id] of tableIds.entries()) {
    const t = await api.table(id), cols = t.cols.map((_, i) => i);
    const cid = cv.addTableCard(id, t.rows.map((r) => r.r), cols, Math.max(0, t.cols.findIndex((x) => CUR.test(x))),
      at ? { x: at.x, y: at.y + k * 420 } : nextPos());
    (cv.cards.find((c) => c.id === cid) as TableCard).checked = true;
  }
  cv.createCompare();
  if (!at) setTimeout(arrange, 500); // ponytail: loadExample과 같은 고정 지연
}
function onAdd(tableId: string, rows: number[], cols: number[]) {
  const cardId = cv.overlay.cardId;
  cv.openSource(null);
  if (cardId) return cv.setRows(cardId, rows);
  cv.addTableCard(tableId, rows, cols, cols[0], nextPos());
}
const nextPos = () => ({ x: 40 + (cv.cards.length % 8) * 30, y: 40 + (cv.cards.length % 8) * 30 });

// 자료 추가 화면에서 선택한 카드들을 캔버스로 끌어다 놓기. 놓은 자리부터 오른쪽으로 펼친다
async function onDrop(e: DragEvent) {
  const raw = e.dataTransfer?.getData("application/x-canvas"); if (!raw) return;
  srcOpen.value = false;
  const at = flow.screenToFlowCoordinate({ x: e.clientX, y: e.clientY });
  let x = at.x;
  for (const p of JSON.parse(raw) as { kind: string; id?: string; tableId?: string; rows?: number[]; tables?: string[] }[]) {
    const pos = { x, y: at.y };
    if (p.kind === "doc") { cv.addDocCard(p.id!, pos); x += 400; }
    else if (p.kind === "table") { await addFound(p.tableId!, p.rows ?? [], pos); x += 640; }
    else if (p.kind === "compare") { await addCompare(p.tables!, pos); x += 1300; }
  }
}
function addMemo() {
  const el = document.querySelector(".flow")!.getBoundingClientRect();
  const id = cv.addMemo("", flow.screenToFlowCoordinate({ x: el.left + el.width / 2 - 140, y: el.top + el.height / 2 - 60 }));
  setTimeout(() => (document.querySelector(`[data-id="${id}"] textarea`) as HTMLElement | null)?.focus(), 50);
}

// 근거 ID → 카드로 이동·강조. [셀ID] = 표ID#r행c열, [doc:ID#f번호]
function focusCite(c: string) {
  let id: string | undefined;
  if (c.startsWith("doc:")) { const doc = c.slice(4).split("#")[0]; id = cv.cards.find((x) => x.kind === "doc" && x.docId === doc)?.id; }
  else { const [tid, cell] = c.split("#"), r = +(cell?.match(/r(\d+)/)?.[1] ?? -1);
    id = (cv.cards.find((x) => x.kind === "table" && x.tableId === tid && x.rows.includes(r)) ?? cv.cards.find((x) => x.kind === "table" && x.tableId === tid))?.id; }
  if (id) focusCard(id);
}
function focusCard(id: string) {
  cv.focus = id;
  flow.fitView({ nodes: [id], padding: 0.6, duration: 300, maxZoom: 1.1 });
  setTimeout(() => { if (cv.focus === id) cv.focus = null; }, 2000);
}

// 자동 배치: 문서·메모는 왼쪽 열, 표 카드는 격자, 비교 카드는 오른쪽 열
const GAP = 60;
function arrange() {
  const size = (id: string) => flow.findNode(id)?.dimensions ?? { width: 400, height: 300 };
  const side = cv.cards.filter((c) => c.kind === "doc" || c.kind === "memo");
  const tables = cv.cards.filter((c) => c.kind === "table"), cmps = cv.cards.filter((c) => c.kind === "compare");
  let sy = 40, sideW = 0;
  for (const c of side) { cv.move(c.id, 40, sy); sy += size(c.id).height + 40; sideW = Math.max(sideW, size(c.id).width); }
  const x0 = side.length ? 40 + sideW + GAP * 2 : 40;
  const per = Math.max(1, Math.ceil(Math.sqrt(tables.length)));
  let x = x0, y = 40, rowH = 0, right = x0;
  tables.forEach((c, i) => {
    if (i && i % per === 0) { x = x0; y += rowH + GAP; rowH = 0; }
    const s = size(c.id);
    cv.move(c.id, x, y);
    x += s.width + GAP; rowH = Math.max(rowH, s.height); right = Math.max(right, x);
  });
  let cy = 40;
  for (const c of cmps) { cv.move(c.id, right, cy); cy += size(c.id).height + GAP;
    for (const ch of cv.cards.filter((x) => x.kind === "chart" && x.compareId === c.id)) { cv.move(ch.id, right, cy); cy += size(ch.id).height + GAP; } }
  setTimeout(() => flow.fitView({ padding: 0.1, duration: 300 }), 200); // ponytail: Vue Flow가 위치를 반영할 때까지 고정 지연. 어긋나면 늘리기
}

async function loadExample() {
  const ex = api.meta.example; if (!ex) return;
  cv.clear();
  const ids: string[] = [];
  for (const e of ex.cards) {
    const t = await api.table(e.table_id);
    const cols = e.cols ?? t.cols.map((_, i) => i);
    ids.push(cv.addTableCard(e.table_id, e.rows ?? t.rows.map((r) => r.r), cols, e.vcol ?? cols[0]));
  }
  const cmpIds: (string | null)[] = [];
  for (const group of ex.compares) {
    for (const c of cv.cards) if (c.kind === "table") c.checked = group.includes(ids.indexOf(c.id));
    cmpIds.push(cv.createCompare());
  }
  for (const i of ex.charts ?? []) if (cmpIds[i]) cv.addChart(cmpIds[i]!);
  const key: Record<string, string> = {};
  ex.cards.forEach((e, i) => (key[e.table_id] ??= ids[i]));
  for (const d of ex.docs) key[d] = cv.addDocCard(d);
  ex.memos.forEach((m, i) => (key[`memo:${i}`] = cv.addMemo(m.text, { x: 0, y: 0 }, m.author, m.type)));
  for (const [s, t, rel] of ex.links) if (key[s] && key[t]) cv.addLink(key[s], key[t], rel);
  setTimeout(arrange, 500); // ponytail: 이벤트 전송(150ms)과 비교 결과 렌더 뒤 배치하도록 고정 지연
}
function clearAll() { if (cv.cards.length && confirm("캔버스의 카드를 모두 지울까요?")) cv.clear(); }

const zoom = computed(() => Math.round(flow.viewport.value.zoom * 100));
const key = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") { e.preventDefault(); srcOpen.value = !srcOpen.value; }
  if (e.key === "Escape") { if (srcOpen.value) srcOpen.value = false; else selLink.value = null; }
};
// 다른 캔버스(세션)를 열면 화면을 그 캔버스에 맞춘다
watch(() => cv.canvasId, () => setTimeout(() => flow.fitView({ padding: 0.1, duration: 300 }), 300));
onMounted(() => { cv.restoreUser(); window.addEventListener("keydown", key); (window as unknown as Record<string, unknown>).__aiSelfCheck = () => selfCheck(api.analysis); });
onUnmounted(() => window.removeEventListener("keydown", key));
</script>

<template>
  <LoginView v-if="!cv.user" />
  <template v-else>
  <CanvasTabs />
  <div class="bar">
    <button v-if="!lpOpen" class="sb" title="왼쪽 사이드바 열기" aria-label="왼쪽 사이드바 열기" @click="lpOpen = true"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2.5" /><path d="M7.5 3.5v13" /></svg></button>
    <h1 class="logo" title="Telescope"><Logo /><span class="sr">Telescope</span></h1>
    <span v-if="api.meta.subtitle" class="sub">{{ api.meta.subtitle }}</span>
    <div class="acts">
      <button class="primary" title="자료 추가 (⌘K)" @click="srcOpen = true">+ 자료 추가</button>
      <button class="line" @click="addMemo">메모</button>
      <button class="line" :disabled="!cv.cards.length" @click="clearAll">비우기</button>
      <button class="sb" :aria-pressed="rpOpen" :title="rpOpen ? '오른쪽 사이드바 닫기' : '오른쪽 사이드바 열기 (프롬프트·결과물·질문·평가)'" aria-label="오른쪽 사이드바" @click="rpOpen = !rpOpen"><svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2.5" /><path d="M12.5 3.5v13" /></svg></button>
    </div>
  </div>
  <div class="main">
    <LeftPanel v-if="lpOpen" @focus="focusCard" @close="lpOpen = false" />
    <div class="flow-wrap" @dragover.prevent @drop.prevent="onDrop">
      <VueFlow id="canvas" class="flow" :nodes="nodes" :edges="edges" :min-zoom="0.2" :max-zoom="2" :default-edge-options="{ type: 'default' }"
        @node-drag-stop="onDragStop" @edge-click="onEdgeClick" @pane-click="selLink = null">
        <template #node-table="p"><TableNode v-bind="p" /></template>
        <template #node-compare="p"><CompareNode v-bind="p" /></template>
        <template #node-doc="p"><DocNode v-bind="p" /></template>
        <template #node-memo="p"><MemoNode v-bind="p" /></template>
        <template #node-chart="p"><ChartNode v-bind="p" /></template>
        <Panel v-if="!cv.cards.length" position="top-center" class="start">
          <b>자료를 캔버스에 올리세요</b>
          <span>상단 + 자료 추가(⌘K)에서 공시 표, IR·뉴스 인사이트, 사내 자료를 찾아 올리고, 카드 오른쪽 점을 다른 카드로 끌어 관계를 잇습니다. 예시도 거기서 불러옵니다.</span>
          <button class="line" @click="srcOpen = true">+ 자료 추가</button>
        </Panel>
        <Panel v-if="link" position="top-center" class="linkbar">
          <span>{{ cardName(link.source) }} → {{ cardName(link.target) }}</span>
          <select :value="link.rel" aria-label="관계" @change="cv.setLink(link.id, ($event.target as HTMLSelectElement).value)">
            <option v-for="r in RELS" :key="r">{{ r }}</option></select>
          <button @click="cv.removeLink(link.id); selLink = null">연결 지우기</button>
          <button aria-label="닫기" @click="selLink = null">✕</button>
        </Panel>
        <Panel position="bottom-left" class="legend">
          <span v-for="r in RELS" :key="r"><i :style="{ background: REL_COLOR[r] }" />{{ r }}</span><span><i class="dash" />같은 개념(자동)</span>
          <small v-if="cv.lastChangedLayer" class="layer">마지막 조작이 바꾼 프롬프트 층: {{ cv.lastChangedLayer }}</small>
        </Panel>
        <!-- 캔버스 도구: 확대·축소 버튼처럼 오른쪽 위에 띄움 -->
        <Panel position="top-right" class="tools">
          <button :disabled="cv.checked.length < 2" @click="cv.createCompare()">표 비교</button>
          <button :disabled="!cv.cards.length" @click="arrange">자동 배치</button>
          <div class="seg" role="group" aria-label="표시 단위">
            <button :aria-pressed="cv.unit === 'raw'" @click="cv.unit = 'raw'">원본 단위</button>
            <button :aria-pressed="cv.unit === 'bil'" @click="cv.unit = 'bil'">십억원</button>
          </div>
          <div class="team" title="같은 부서 팀원. 고르면 그 사람 이름으로 조작이 기록됩니다">
            <span v-for="m in cv.members" :key="m.name" class="av" :data-me="m.name === cv.author" :title="who(m.name)">{{ m.name[0] }}</span>
            <select v-model="cv.author" aria-label="작성자"><option v-for="m in cv.members" :key="m.name" :value="m.name">{{ m.name }} · {{ m.job }}</option></select>
          </div>
        </Panel>
        <Panel position="bottom-right" class="zoom">
          <button aria-label="축소" @click="flow.zoomOut({ duration: 150 })">−</button>
          <button title="화면에 맞추기" @click="flow.fitView({ padding: 0.1, duration: 300 })">{{ zoom }}%</button>
          <button aria-label="확대" @click="flow.zoomIn({ duration: 150 })">+</button>
        </Panel>
      </VueFlow>
    </div>
    <ResultPanel v-if="rpOpen" :tab="rp" @tab="(t) => (rp = t)" @cite="focusCite" />
  </div>
  <SourceModal :open="srcOpen" @close="srcOpen = false" @pick="onPick" @add-table="addFound"
    @add-doc="(id) => cv.addDocCard(id, nextPos())" @add-compare="addCompare"
    @example="srcOpen = false; loadExample()" />
  <SourceOverlay :table-id="cv.overlay.tableId" :match="cv.overlay.match" :card-id="cv.overlay.cardId"
    @close="cv.openSource(null)" @add="onAdd" />
  </template>
</template>
