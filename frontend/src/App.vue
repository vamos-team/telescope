<script setup lang="ts">
import { computed, ref, watch, onMounted, onUnmounted } from "vue";
import { storeToRefs } from "pinia";
import { VueFlow, Panel, MarkerType, useVueFlow, type Node, type Edge, type NodeDragEvent, type EdgeMouseEvent } from "@vue-flow/core";
import SourceModal, { type Pick } from "./components/SourceModal.vue";
import ResultPanel from "./components/ResultPanel.vue";
import SourceOverlay from "./components/SourceOverlay.vue";
import TableNode from "./components/nodes/TableNode.vue";
import CompareNode from "./components/nodes/CompareNode.vue";
import DocNode from "./components/nodes/DocNode.vue";
import PromptNode from "./components/nodes/PromptNode.vue";
import DashNode from "./components/nodes/DashNode.vue";
import MenuDrawer from "./components/MenuDrawer.vue";
import ChartNode from "./components/nodes/ChartNode.vue";
import LoginView from "./components/LoginView.vue";
import LeftPanel from "./components/LeftPanel.vue";
import Logo from "./components/Logo.vue";
import HomeView from "./components/HomeView.vue";
import ShareDialog from "./components/ShareDialog.vue";
import NewCanvasDialog from "./components/NewCanvasDialog.vue";
import { useCanvas, RELS, quarterOf, type TableCard } from "./stores/canvas";
import { SYNC_MODE } from "./sync/config";
import { api } from "./api";
import { selfCheck } from "./local/ai";

const cv = useCanvas();
const flow = useVueFlow("canvas");
const srcOpen = ref(false);  // 자료 검색 화면
const shareOpen = ref(false);  // 공유 창
const renaming = ref(false);  // 상단 캔버스 제목 이름 바꾸기
const vFocus = { mounted: (el: HTMLInputElement) => el.select() };
const menuOpen = ref(false);  // 로고를 누르면 뜨는 사이드 팝업
const run = ref(0);  // 생성하기 횟수. 0이면 오른쪽 결과 패널 없음, 누르면 열려 결과물·질문·평가를 한 번에 만듦
const selLink = ref<string | null>(null);

// 카드를 고르면 그 카드와 선 하나로 이어진 카드만 밝게, 나머지 카드·선은 흐리게 (빈 곳 클릭·Esc로 해제)
const { active } = storeToRefs(cv);
// 선 종류별 보이기 (범례 칩). "같은 개념" 자동 점선은 기본 꺼짐: 카드를 고르면 그 카드에 닿는 것만 보임
const SRC_KIND = "비교 원본", AUTO_KIND = "같은 개념";
const KINDS = [...RELS, SRC_KIND, AUTO_KIND];
const shown = ref(new Set<string>(KINDS.filter((k) => k !== AUTO_KIND)));
function toggleKind(k: string) { const s = new Set(shown.value); s.has(k) ? s.delete(k) : s.add(k); shown.value = s; }

// 선 프롬프트가 있는 선은 보라 라벨 (class "hasp")
const REL_COLOR: Record<string, string> = { 근거: "var(--green)", 설명: "var(--primary-text)", 반박: "var(--red)", 관련: "var(--t-cap)" };
const name = (t: string) => api.analysis.tag_names[t] ?? t;
// 세 종류 선을 모두 만든 원본 목록 (data.kind로 구분). 화면에는 아래 edges에서 걸러 보여 준다
const allEdges = computed<Edge[]>(() => {
  const out: Edge[] = cv.cards.flatMap((c) => c.kind === "compare"
    ? c.sources.map((s) => ({ id: `${s}-${c.id}`, source: s, target: c.id, selectable: false, class: "src", data: { kind: SRC_KIND } }))
    : c.kind === "chart" ? [{ id: `${c.compareId}-${c.id}`, source: c.compareId, target: c.id, selectable: false, class: "src", data: { kind: SRC_KIND } }] : []);
  for (const l of cv.links) out.push({ id: l.id, source: l.source, target: l.target, label: l.prompt ? `${l.rel} · 프롬프트` : l.rel, markerEnd: MarkerType.ArrowClosed, data: { kind: l.rel },
    class: [selLink.value === l.id ? "sel" : "", l.prompt ? "hasp" : ""].filter(Boolean).join(" "), style: { stroke: REL_COLOR[l.rel], strokeWidth: 2, opacity: 1 }, labelBgPadding: [6, 3], labelBgBorderRadius: 6 });
  // 온톨로지 자동 연결: 문서 카드와 공시 표 카드가 같은 개념(태그)을 가지면 점선으로 잇는다. 라벨은 개념 이름만
  const linked = new Set(cv.links.flatMap((l) => [l.source + l.target, l.target + l.source]));
  for (const d of cv.cards.filter((c) => c.kind === "doc")) for (const t of cv.cards.filter((c) => c.kind === "table")) {
    const shared = (cv.tags[d.id] ?? []).filter((x) => (cv.tags[t.id] ?? []).includes(x));
    if (shared.length && !linked.has(d.id + t.id)) out.push({ id: `auto-${d.id}-${t.id}`, source: d.id, target: t.id, selectable: false,
      label: shared.map(name).join(", "), class: "auto", data: { kind: AUTO_KIND } });
  }
  return out;
});
const touches = (e: Edge) => e.source === active.value || e.target === active.value;
const related = computed(() => active.value ? new Set(allEdges.value.filter(touches).flatMap((e) => [e.source, e.target])) : null);
const edges = computed<Edge[]>(() => allEdges.value.flatMap((e) => {
  const k = e.data.kind, hot = !!active.value && touches(e);
  if (k === AUTO_KIND ? !(shown.value.has(k) || hot) : !shown.value.has(k)) return [];
  const cls = [e.class, active.value ? (hot ? "hot" : "dim") : ""].filter(Boolean).join(" ");
  return [{ ...e, class: cls, animated: k === SRC_KIND && hot, label: k === AUTO_KIND && !hot ? undefined : e.label }];
}));
// 캔버스 상태 → Vue Flow 노드 (좌표는 store가 원본). 하이라이트 class: act(고른 카드) / rel(이어진 카드) / dim
const nodes = computed<Node[]>(() => cv.cards.map((c) => ({ id: c.id, type: c.kind, position: { x: c.x, y: c.y },
  data: { cardId: c.id }, dragHandle: ".drag-handle",
  class: !related.value ? "" : c.id === active.value ? "act" : related.value.has(c.id) ? "rel" : "dim" })));
// 여러 카드를 함께 끌어도 모든 카드의 좌표를 저장 (e.nodes에 끈 카드 전부)
const onDragStop = (e: NodeDragEvent) => { for (const n of e.nodes) cv.move(n.id, n.position.x, n.position.y); };
flow.onConnect((c) => cv.addLink(c.source, c.target));
const onEdgeClick = ({ edge }: EdgeMouseEvent) => { if (!cv.readOnly && cv.links.some((l) => l.id === edge.id)) selLink.value = edge.id; };
const link = computed(() => cv.links.find((l) => l.id === selLink.value) ?? null);
const cardName = (id: string) => cv.cardTitle(id);
// 왼쪽 프롬프트 목록에서 선 프롬프트를 누르면: 양 끝 카드를 화면에 맞추고 그 선을 선택(선 프롬프트 칸이 열림)
function focusLink(id: string) {
  const l = cv.links.find((x) => x.id === id); if (!l) return;
  selLink.value = id; active.value = null;
  flow.fitView({ nodes: [l.source, l.target], padding: 0.4, duration: 300, maxZoom: 1.1 });
}

async function addFound(tableId: string, rows: number[], pos = nextPos(), cols?: number[]) {
  const t = await api.table(tableId), all = t.cols.map((_, i) => i);
  cols = cols?.length ? cols : all;
  cv.addTableCard(tableId, rows.length ? rows : t.rows.map((r) => r.r), cols, cols[0], pos);
}
// 인사이트의 "비교 카드로 올리기": 3사 표를 올려 비교 카드로 묶는다. 값 열은 1Q26(당분기)
const CUR = /1Q26|당분기|제 10 기 1분기/;
// 비동기 작업 중 다른 캔버스로 바뀌면 멈춤 (캔버스를 열 때마다 sessionId가 새로 생김)
const sameCanvas = (sid: string) => () => cv.sessionId === sid;
async function addCompare(tableIds: string[], at?: { x: number; y: number }) {
  if (!cv.canEdit) return;
  const same = sameCanvas(cv.sessionId);
  srcOpen.value = false;
  for (const c of cv.cards) if (c.kind === "table") c.checked = false;
  for (const [k, id] of tableIds.entries()) {
    const t = await api.table(id), cols = t.cols.map((_, i) => i);
    if (!same()) return;
    const cid = cv.addTableCard(id, t.rows.map((r) => r.r), cols, Math.max(0, t.cols.findIndex((x) => CUR.test(x))),
      at ? { x: at.x, y: at.y + k * 420 } : nextPos());
    (cv.cards.find((c) => c.id === cid) as TableCard).checked = true;
  }
  cv.createCompare();
  if (!at) setTimeout(() => same() && arrange(), 500); // ponytail: loadExample과 같은 고정 지연
}
function onAdd(tableId: string, rows: number[], cols: number[]) {
  const cardId = cv.overlay.cardId;
  cv.openSource(null);
  if (cardId) return cv.setRows(cardId, rows);
  cv.addTableCard(tableId, rows, cols, cols[0], nextPos());
}
const nextPos = () => ({ x: 40 + (cv.cards.length % 8) * 30, y: 40 + (cv.cards.length % 8) * 30 });

// 자료 검색 화면의 항목을 캔버스에 놓기: 놓은 자리(끌기) 또는 화면 가운데(추가하기)부터 오른쪽으로 펼친다
async function place(items: Pick[], at: { x: number; y: number }) {
  if (!cv.canEdit) return;  // 보기 권한이면 다른 창에서 끌어와도 무시
  srcOpen.value = false;
  let x = at.x;
  for (const p of items) {
    const pos = { x, y: at.y };
    if (p.kind === "doc") { cv.addDocCard(p.id!, pos); x += 400; }
    else if (p.kind === "table") { await addFound(p.tableId!, p.rows ?? [], pos, p.cols); x += 640; }
    else if (p.kind === "compare") { await addCompare(p.tables!, pos); x += 1300; }
    else if (p.kind === "dash") { cv.addDash(p.panel!, pos); x += 600; }
  }
}
function onDrop(e: DragEvent) {
  const raw = e.dataTransfer?.getData("application/x-canvas"); if (!raw) return;
  place(JSON.parse(raw) as Pick[], flow.screenToFlowCoordinate({ x: e.clientX, y: e.clientY }));
}
const center = (dx = 0, dy = 0) => { const el = document.querySelector(".flow")!.getBoundingClientRect();
  return flow.screenToFlowCoordinate({ x: el.left + el.width / 2 - dx, y: el.top + el.height / 2 - dy }); };
function addPrompt() {
  const id = cv.addPrompt("", center(150, 80));
  setTimeout(() => (document.querySelector(`[data-id="${id}"] textarea`) as HTMLElement | null)?.focus(), 50);
}

// 근거 ID → 카드로 이동·강조. [셀ID] = 표ID#r행c열, [doc:ID#f번호]
function focusCite(c: string) {
  let id: string | undefined;
  if (c.startsWith("dash:")) { const panel = c.slice(5).split("#")[0]; id = cv.cards.find((x) => x.kind === "dash" && x.panel === panel)?.id; }
  else if (c.startsWith("doc:")) { const doc = c.slice(4).split("#")[0]; id = cv.cards.find((x) => x.kind === "doc" && x.docId === doc)?.id; }
  else { const [tid, cell] = c.split("#"), r = +(cell?.match(/r(\d+)/)?.[1] ?? -1);
    id = (cv.cards.find((x) => x.kind === "table" && x.tableId === tid && x.rows.includes(r)) ?? cv.cards.find((x) => x.kind === "table" && x.tableId === tid))?.id; }
  if (id) focusCard(id);
}
function focusCard(id: string) {
  cv.focus = id; active.value = id;
  flow.fitView({ nodes: [id], padding: 0.6, duration: 300, maxZoom: 1.1 });
  setTimeout(() => { if (cv.focus === id) cv.focus = null; }, 2000);
}

// 자동 배치: 문서·메모는 왼쪽 열, 표 카드는 격자, 비교 카드는 오른쪽 열
const GAP = 60;
function arrange() {
  const size = (id: string) => flow.findNode(id)?.dimensions ?? { width: 400, height: 300 };
  const side = cv.cards.filter((c) => c.kind === "doc" || c.kind === "prompt");
  const tables = cv.cards.filter((c) => c.kind === "table" || c.kind === "dash"), cmps = cv.cards.filter((c) => c.kind === "compare");
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
  const ex = api.meta.example; if (!ex || !cv.canEdit) return;
  const same = sameCanvas(cv.sessionId);
  cv.clear();
  const ids: string[] = [];
  for (const e of ex.cards) {
    const t = await api.table(e.table_id);
    if (!same()) return;
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
  ex.prompts.forEach((t, i) => (key[`prompt:${i}`] = cv.addPrompt(t)));
  for (const [s, t, rel] of ex.links) if (key[s] && key[t]) cv.addLink(key[s], key[t], rel);
  setTimeout(() => same() && arrange(), 500); // ponytail: 이벤트 전송(150ms)과 비교 결과 렌더 뒤 배치하도록 고정 지연
}
function clearAll() { if (cv.cards.length && confirm("캔버스의 카드를 모두 지울까요?")) cv.clear(); }

const zoom = computed(() => Math.round(flow.viewport.value.zoom * 100));

// ---- 서버 모드: 다른 사람 커서와 접속자 (docs/backend/api-spec.md 5.6) ----
const realtime = SYNC_MODE === "server";
/** 다른 사람 커서를 화면 좌표로 바꾼다(캔버스 좌표 × 확대 + 이동). 사람마다 색을 고정한다. */
const remoteCursors = computed(() => {
  const { x: vx, y: vy, zoom: z } = flow.viewport.value;
  return Object.entries(cv.cursors).map(([id, c]) => ({
    id, left: c.x * z + vx, top: c.y * z + vy, color: `hsl(${(Number(id) * 67) % 360} 70% 45%)`, label: `사용자 ${id}`,
  }));
});
/** 내 마우스 위치를 캔버스 좌표로 바꿔 보낸다(보내는 빈도는 연결 쪽에서 50ms로 제한). */
function onPointerMove(e: MouseEvent) {
  if (!realtime) return;
  const p = flow.screenToFlowCoordinate({ x: e.clientX, y: e.clientY });
  cv.sendCursor(p.x, p.y);
}
const key = (e: KeyboardEvent) => {
  if (!cv.canvasId) return;  // 캔버스 홈에서는 캔버스 단축키 없음
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k" && !cv.readOnly) { e.preventDefault(); srcOpen.value = !srcOpen.value; }
  if (e.key === "Escape") { if (shareOpen.value) shareOpen.value = false; else if (menuOpen.value) menuOpen.value = false; else if (srcOpen.value) srcOpen.value = false; else { selLink.value = null; active.value = null; } }
};
// 다른 캔버스(세션)를 열면 화면을 그 캔버스에 맞춘다
watch(() => cv.canvasId, () => { selLink.value = null; active.value = null; shareOpen.value = false; menuOpen.value = false; run.value = 0; setTimeout(() => flow.fitView({ padding: 0.1, duration: 300 }), 300); });
// 열어 둔 캔버스에 대한 권한이 사라지면(공유 창에서 내 권한 삭제 등) 홈으로
watch(() => !!cv.canvasId && !cv.meta, (lost) => { if (lost) { shareOpen.value = false; cv.goHome(); } });
onMounted(() => { cv.restoreUser(); window.addEventListener("keydown", key); (window as unknown as Record<string, unknown>).__aiSelfCheck = () => selfCheck(api.analysis); });
onUnmounted(() => window.removeEventListener("keydown", key));
</script>

<template>
  <LoginView v-if="!cv.user" />
  <template v-else>
  <HomeView v-if="!cv.canvasId" />
  <template v-else>
  <div class="bar">
    <h1 class="logo"><button class="home" title="메뉴 (홈, 나의 캔버스, 캔버스 정보)" aria-label="메뉴" @click="menuOpen = true"><Logo /></button></h1>
    <input v-if="renaming" class="ctitle" :value="cv.canvasName" aria-label="캔버스 이름" v-focus
      @keydown.enter="($event.target as HTMLInputElement).blur()" @blur="cv.rename(($event.target as HTMLInputElement).value); renaming = false" />
    <b v-else class="ctitle" :title="cv.readOnly ? cv.canvasName : '두 번 눌러 이름 바꾸기'" @dblclick="renaming = !cv.readOnly">{{ cv.canvasName }}</b>
    <span class="sub">{{ quarterOf(cv.meta) }}</span>
    <div class="acts">
      <span v-if="realtime && cv.present.length" class="presence" :title="cv.present.map((id) => `사용자 ${id}`).join(', ')">
        접속 {{ cv.present.length }}명</span>
      <span v-if="cv.readOnly" class="ro-note">보기 전용</span>
      <template v-else>
        <button class="grey" title="자료 검색 (⌘K)" @click="srcOpen = true">자료 검색</button>
        <button class="pline" @click="addPrompt">프롬프트</button>
      </template>
      <button class="primary" :disabled="!cv.cards.length" title="캔버스 컨텍스트로 결과물·질문·평가를 한 번에 생성" @click="run++">생성하기</button>
    </div>
  </div>
  <div class="main">
    <LeftPanel @focus="focusCard" @link="focusLink" />
    <div class="flow-wrap" :class="{ ro: cv.readOnly }" @dragover.prevent @drop.prevent="onDrop" @mousemove="onPointerMove">
      <VueFlow id="canvas" class="flow" :nodes-draggable="!cv.readOnly" :nodes-connectable="!cv.readOnly" :nodes="nodes" :edges="edges" :min-zoom="0.2" :max-zoom="2" :default-edge-options="{ type: 'default' }" :delete-key-code="null"
        @node-drag-stop="onDragStop" @edge-click="onEdgeClick" @node-click="({ node }) => (active = node.id)" @pane-click="selLink = null; active = null">
        <template #node-table="p"><TableNode v-bind="p" /></template>
        <template #node-compare="p"><CompareNode v-bind="p" /></template>
        <template #node-doc="p"><DocNode v-bind="p" /></template>
        <template #node-prompt="p"><PromptNode v-bind="p" /></template>
        <template #node-dash="p"><DashNode v-bind="p" /></template>
        <template #node-chart="p"><ChartNode v-bind="p" /></template>
        <Panel v-if="cv.error" position="top-center" class="linkbar err-bar" role="alert">
          <span>{{ cv.error }}</span><button @click="cv.error = ''">닫기</button>
        </Panel>
        <Panel v-if="link && cv.canEdit" position="top-center" class="linkbar">
          <span>{{ cardName(link.source) }} → {{ cardName(link.target) }}</span>
          <select :value="link.rel" aria-label="관계" @change="cv.setLink(link.id, ($event.target as HTMLSelectElement).value)">
            <option v-for="r in RELS" :key="r">{{ r }}</option></select>
          <button @click="cv.removeLink(link.id); selLink = null">연결 지우기</button>
          <button aria-label="닫기" @click="selLink = null">✕</button>
          <textarea :key="link.id" class="lprompt" :value="link.prompt ?? ''" rows="2" aria-label="선 프롬프트"
            :placeholder="`이 ${link.rel} 관계를 어떻게 쓸지 적으세요 (예: 이 근거로 반박 문단을 쓸 것)`"
            @blur="cv.setLinkPrompt(link.id, ($event.target as HTMLTextAreaElement).value.trim())" />
        </Panel>
        <Panel position="bottom-left" class="legend">
          <button v-for="k in KINDS" :key="k" :aria-pressed="shown.has(k)" :title="shown.has(k) ? k + ' 선 숨기기' : k + ' 선 보이기'" @click="toggleKind(k)">
            <i :class="{ dash: k === AUTO_KIND, src: k === SRC_KIND }" :style="REL_COLOR[k] ? { background: REL_COLOR[k] } : {}" />{{ k === AUTO_KIND ? "같은 개념(자동)" : k }}</button>
        </Panel>
        <!-- 캔버스 도구: 확대·축소 버튼처럼 오른쪽 위에 띄움 -->
        <Panel v-if="!cv.readOnly" position="top-right" class="tools">
          <button :disabled="!cv.cards.length" @click="arrange">자동 배치</button>
          <button :disabled="!cv.cards.length" @click="clearAll">비우기</button>
          <div class="seg" role="group" aria-label="표시 단위">
            <button :aria-pressed="cv.unit === 'raw'" @click="cv.unit = 'raw'">원본 단위</button>
            <button :aria-pressed="cv.unit === 'bil'" @click="cv.unit = 'bil'">십억원</button>
          </div>
        </Panel>
        <!-- 다른 사람 커서: 눌리지 않는 층으로 띄움 -->
        <div v-if="realtime" class="cursors" aria-hidden="true">
          <div v-for="c in remoteCursors" :key="c.id" class="cursor" :style="{ left: c.left + 'px', top: c.top + 'px', '--c': c.color }">
            <svg width="14" height="18" viewBox="0 0 14 18"><path d="M1 1l12 7-5 1.5L5.5 17z" /></svg><span>{{ c.label }}</span>
          </div>
        </div>
        <Panel position="bottom-right" class="zoom">
          <button aria-label="축소" @click="flow.zoomOut({ duration: 150 })">−</button>
          <button title="화면에 맞추기" @click="flow.fitView({ padding: 0.1, duration: 300 })">{{ zoom }}%</button>
          <button aria-label="확대" @click="flow.zoomIn({ duration: 150 })">+</button>
        </Panel>
      </VueFlow>
    </div>
    <!-- 생성하기를 누르기 전에는 없음. 캔버스가 바뀌면 key로 새로 만들어 다른 캔버스 결과가 섞이지 않게 -->
    <ResultPanel v-if="run" :key="cv.canvasId" :run="run" @cite="focusCite" />
  </div>
  <SourceModal :open="srcOpen" @close="srcOpen = false" @add="(items) => place(items, center(300, 200))"
    @example="srcOpen = false; loadExample()" />
  <SourceOverlay :table-id="cv.overlay.tableId" :match="cv.overlay.match" :card-id="cv.overlay.cardId"
    @close="cv.openSource(null)" @add="onAdd" />
  <MenuDrawer v-if="menuOpen" @close="menuOpen = false" @edit="menuOpen = false; shareOpen = true" />
  <ShareDialog v-if="shareOpen && cv.meta" @close="shareOpen = false" />
  </template>
  <NewCanvasDialog v-if="cv.creating" />
  </template>
</template>
