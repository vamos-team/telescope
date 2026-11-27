<script setup lang="ts">
// 페이지 = 캔버스(사용자가 근거를 모음) + 분석(AI 검토 · 질문 · 평가). 최좌측 아이콘 레일로 두 화면을 오감
// 헤더: 로고(누르면 홈) · 페이지 제목 · 기준 시점 · 오른쪽 페이지 정보(협업자 편집)
import { computed, ref, watch, onMounted, onUnmounted, nextTick } from "vue";
import { storeToRefs } from "pinia";
import { VueFlow, Panel, MarkerType, ConnectionMode, useVueFlow, type Node, type Edge, type NodeDragEvent, type EdgeMouseEvent, type NodeMouseEvent } from "@vue-flow/core";
import SourcePanel, { type Pick } from "./components/SourcePanel.vue";
import AnalysisView from "./components/AnalysisView.vue";
import SourceOverlay from "./components/SourceOverlay.vue";
import TableNode from "./components/nodes/TableNode.vue";
import CompareNode from "./components/nodes/CompareNode.vue";
import DocNode from "./components/nodes/DocNode.vue";
import MemoNode from "./components/nodes/MemoNode.vue";
import DashNode from "./components/nodes/DashNode.vue";
import ChartNode from "./components/nodes/ChartNode.vue";
import MemoEditor from "./components/MemoEditor.vue";
import LinkEdge from "./components/nodes/LinkEdge.vue";
import LoginView from "./components/LoginView.vue";
import LeftPanel from "./components/LeftPanel.vue";
import Logo from "./components/Logo.vue";
import HomeView from "./components/HomeView.vue";
import PageInfo from "./components/PageInfo.vue";
import NewCanvasDialog from "./components/NewCanvasDialog.vue";
import { useCanvas, type Card, type TableCard } from "./stores/canvas";
import { SYNC_MODE } from "./sync/config";
import { api } from "./api";
import { selfCheck } from "./local/ai";

const cv = useCanvas();
const flow = useVueFlow("canvas");
const view = ref<"canvas" | "analysis">("canvas");
const lpOpen = ref(true), spOpen = ref(false);  // 캔버스 왼쪽(근거 데이터 · 메모) / 오른쪽(자료 검색) 사이드바. 오른쪽은 캔버스를 열 때마다 닫힌 채 시작
// 오른쪽 사이드바 폭: 처음 열면 캔버스 영역의 절반, 왼쪽 경계선을 끌어 조정 (360px ~ 화면의 75%)
const cview = ref<HTMLElement>(), spW = ref(0);
const fitSp = () => { if (!spW.value && cview.value) spW.value = Math.round(cview.value.clientWidth / 2); };
function resizeSp(e: PointerEvent) {
  const x0 = e.clientX, w0 = spW.value, max = (cview.value?.clientWidth ?? 1200) * 0.75;
  const move = (m: PointerEvent) => { spW.value = Math.round(Math.min(max, Math.max(360, w0 + x0 - m.clientX))); };
  const up = () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); document.body.classList.remove("resizing"); };
  window.addEventListener("pointermove", move); window.addEventListener("pointerup", up); document.body.classList.add("resizing");
}
const linkMemo = ref(false);  // 연결 바에서 메모 입력 칸 펼침
const infoOpen = ref(false);  // 페이지 정보 팝오버
const renaming = ref(false);
const vFocus = { mounted: (el: HTMLInputElement) => el.select() };
const run = ref(0);  // 분석하기 누른 횟수 (분석 화면이 받아 새 세션 시작)
const flagGo = ref<{ id: string; n: number } | null>(null);  // 캔버스 지적 라벨 → 분석 화면의 그 항목
const selLink = ref<string | null>(null);

// 카드를 고르면 그 카드와 선 하나로 이어진 카드만 밝게(카드 성격색), 나머지는 흐리게 (빈 곳 클릭 · Esc로 해제)
const { active } = storeToRefs(cv);
function selectLink(id: string) {
  selLink.value = id; active.value = null; cv.selected = [];
}
function selectCard(id: string) {
  selLink.value = null; active.value = id; cv.selected = [id];
}
// 선 종류: 사용자 연결, 비교 원본(비교 · 시각화 카드와 원본 표), 같은 개념(자동 점선)
const SRC_KIND = "비교 원본", LINK_KIND = "연결";
const shown = new Set<string>([LINK_KIND, SRC_KIND]);

// 카드 성격(강조색): dart 공시 / ir / news / internal / viz / memo
const srcOf = (c: Card) => c.kind === "table" ? "dart" : c.kind === "doc" ? ({ ar: "ir" } as Record<string, string>)[api.docs.get(c.docId)?.kind ?? ""] ?? api.docs.get(c.docId)?.kind ?? "internal"
  : c.kind === "memo" ? "memo" : "viz";
// 선은 좌우로만: 출발 카드가 도착 카드보다 왼쪽이면 오른쪽 점 → 왼쪽 점, 반대면 왼쪽 점 → 오른쪽 점 (위아래로 돌아가는 선 없음)
const cx = (id: string) => { const c = cv.cards.find((k) => k.id === id); return c ? c.x + (flow.findNode(id)?.dimensions.width ?? 400) / 2 : 0; };
const sides = (a: string, b: string) => cx(a) <= cx(b) ? { sourceHandle: "sr", targetHandle: "tl" } : { sourceHandle: "sl", targetHandle: "tr" };
const allEdges = computed<Edge[]>(() => {
  const out: Edge[] = cv.cards.flatMap((c) => c.kind === "compare" || c.kind === "chart"
    ? c.sources.map((s) => ({ id: `${s}-${c.id}`, source: s, target: c.id, ...sides(s, c.id), selectable: false, class: "src", data: { kind: SRC_KIND } })) : []);
  // 사용자 선: 관계 태그 없음. 선 메모가 있으면 "메모" 라벨
  // 사용자 선: 메모 없으면 점선, 메모 있으면 진한 실선 + 가운데 작성자 아바타 (LinkEdge)
  for (const l of cv.links) out.push({ id: l.id, type: "link", source: l.source, target: l.target, ...sides(l.source, l.target), markerEnd: MarkerType.ArrowClosed,
    data: { kind: LINK_KIND, memo: l.memo, by: l.memoBy ?? (l.memo ? cv.author : undefined), onOpen: selectLink },
    class: [selLink.value === l.id ? "sel" : "", l.memo ? "hasm" : "nomemo"].filter(Boolean).join(" "), style: { strokeWidth: l.memo ? 2.2 : 1.6, opacity: 1 } });
  // 같은 개념 자동 점선은 없앰 (카드 태그는 분석 · 검색 추천에만 씀)
  return out;
});
const touches = (e: Edge) => e.source === active.value || e.target === active.value;
const related = computed(() => active.value ? new Set(allEdges.value.filter(touches).flatMap((e) => [e.source, e.target])) : null);
const edges = computed<Edge[]>(() => allEdges.value.flatMap((e) => {
  const k = e.data.kind, hot = !!active.value && touches(e);
  if (!shown.has(k)) return [];
  const cls = [e.class, active.value ? (hot ? "hot" : "dim") : ""].filter(Boolean).join(" ");
  return [{ ...e, class: cls, animated: k === SRC_KIND && hot, label: e.label }];
}));
const openFlag = (id: string) => { view.value = "analysis"; flagGo.value = { id, n: (flagGo.value?.n ?? 0) + 1 }; };
// 캔버스 상태 → Vue Flow 노드. class: src-*(강조색) · act/rel/dim(이어진 카드) · picked(Shift 다중 선택) · flag-*(검토 지적)
const nodes = computed<Node[]>(() => cv.cards.map((c) => {
  const fl = cv.flags[c.id], lv = fl?.some((f) => f.level === "bad") ? "bad" : fl?.length ? "warn" : "";
  const multi = cv.selected.length > 1;
  return { id: c.id, type: c.kind, position: { x: c.x, y: c.y }, data: { cardId: c.id, onFlag: openFlag }, dragHandle: ".drag-handle",
    class: [`src-${srcOf(c)}`, lv && `flag-${lv}`, multi && cv.selected.includes(c.id) ? "picked" : "",
      multi ? "" : !related.value ? "" : c.id === active.value ? "act" : related.value.has(c.id) ? "rel" : "dim"].filter(Boolean).join(" ") };
}));
const onDragStop = (e: NodeDragEvent) => { for (const n of e.nodes) cv.move(n.id, n.position.x, n.position.y); };
flow.onConnect((c) => cv.addLink(c.source, c.target));
const onEdgeClick = ({ edge }: EdgeMouseEvent) => { if (cv.links.some((l) => l.id === edge.id)) selectLink(edge.id); };
watch(selLink, () => { linkMemo.value = !!cv.links.find((l) => l.id === selLink.value)?.memo; });
const link = computed(() => cv.links.find((l) => l.id === selLink.value) ?? null);
// 카드 누르기: 그냥 누르면 그 카드 하나(이어진 카드 강조), Shift를 누른 채면 여러 장 고르기
function onNodeClick({ node, event }: NodeMouseEvent) {
  selLink.value = null;
  if ((event as MouseEvent).shiftKey) {
    const base = cv.selected.length ? cv.selected : active.value ? [active.value] : [];
    cv.selected = base.includes(node.id) ? base.filter((x) => x !== node.id) : [...base, node.id];
    active.value = cv.selected.length === 1 ? cv.selected[0] : null;
  } else selectCard(node.id);
}
const clearSel = () => { selLink.value = null; active.value = null; cv.selected = []; trashOpen.value = false; };
const pickedTables = computed(() => cv.selected.filter((id) => cv.cards.find((c) => c.id === id)?.kind === "table"));
// 고른 표들 사이에 같은 개념(계정)이 하나라도 있는지: 없으면 만들어도 비교 · 막대가 비어서 미리 알림
// 메모 1장 + 다른 카드 여러 장을 고르면 메모를 그 카드들에 한 번에 잇는다 (메모 하나가 여러 카드에 연결)
const pickedMemo = computed(() => { const ms = cv.selected.filter((id) => cv.cards.find((c) => c.id === id)?.kind === "memo");
  return ms.length === 1 && cv.selected.length > 1 ? ms[0] : null; });
function memoToAll() { const m = pickedMemo.value; if (!m) return; for (const id of cv.selected) if (id !== m) cv.addLink(m, id); clearSel(); }
function makeTable() { const id = cv.createCompare(pickedTables.value); if (id) { clearSel(); setTimeout(() => focusCard(id), 200); } }
function makeChart() { const id = cv.addChartFrom(pickedTables.value); if (id) { clearSel(); setTimeout(() => focusCard(id), 200); } }
// 왼쪽 메모 목록에서 선 메모를 누르면: 양 끝 카드를 화면에 맞추고 그 선을 선택
function focusLink(id: string) {
  const l = cv.links.find((x) => x.id === id); if (!l) return;
  view.value = "canvas"; selectLink(id);
  nextTick(() => flow.fitView({ nodes: [l.source, l.target], padding: 0.4, duration: 300, maxZoom: 1.1 }));
}

async function addFound(tableId: string, rows: number[], pos = nextPos(), cols?: number[]) {
  const t = await api.table(tableId), all = t.cols.map((_, i) => i);
  cols = cols?.length ? cols : all;
  cv.addTableCard(tableId, rows.length ? rows : t.rows.map((r) => r.r), cols, cols[0], pos);
}
// 인사이트의 3사 비교: 3사 표를 올려 비교 카드로 묶는다. 값 열은 1Q26(당분기)
const CUR = /1Q26|당분기|제 10 기 1분기/;
const sameCanvas = (sid: string) => () => cv.sessionId === sid;
async function addCompare(tableIds: string[], at?: { x: number; y: number }) {
  if (!cv.canEdit) return;
  const same = sameCanvas(cv.sessionId);
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

// 자료 검색 사이드바의 항목을 캔버스에 놓기: 놓은 자리(끌기) 또는 화면 가운데(두 번 누르기 · 데이터 입력)부터 오른쪽으로 펼친다
async function place(items: Pick[], at: { x: number; y: number }) {
  if (!cv.canEdit) return;
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
function addMemo() {
  const id = cv.addMemo("", center(140, 60));
  setTimeout(() => (document.querySelector(`[data-id="${id}"] textarea`) as HTMLElement | null)?.focus(), 80);
}
// 자료 추가 · ⌘K: 사이드바만 열고 탭 화면부터 보여 줌 (검색창은 자동으로 고르지 않음)
function openSearch() { spOpen.value = true; nextTick(fitSp); }
function analyze() { if (!cv.cards.length) return; view.value = "analysis"; run.value++; }

// 근거 ID → 캔버스 화면으로 바꾸고 카드로 이동 · 강조. [셀ID] = 표ID#r행c열, [doc:ID#f번호], [dash:패널#행@열]
function focusCite(c: string) {
  let id: string | undefined;
  if (c.startsWith("dash:")) { const panel = c.slice(5).split("#")[0]; id = cv.cards.find((x) => x.kind === "dash" && x.panel === panel)?.id; }
  else if (c.startsWith("doc:")) { const doc = c.slice(4).split("#")[0]; id = cv.cards.find((x) => x.kind === "doc" && x.docId === doc)?.id; }
  else { const [tid, cell] = c.split("#"), r = +(cell?.match(/r(\d+)/)?.[1] ?? -1);
    id = (cv.cards.find((x) => x.kind === "table" && x.tableId === tid && x.rows.includes(r)) ?? cv.cards.find((x) => x.kind === "table" && x.tableId === tid))?.id; }
  if (id) focusCard(id);
}
function focusCard(id: string) {
  view.value = "canvas";
  cv.focus = id; selectCard(id);
  nextTick(() => flow.fitView({ nodes: [id], padding: 0.6, duration: 300, maxZoom: 1.1 }));
  setTimeout(() => { if (cv.focus === id) cv.focus = null; }, 2000);
}

// 자동 배치: 문서·메모는 왼쪽 열, 표 카드는 격자, 비교 · 시각화 카드는 오른쪽 열
const GAP = 60;
function arrange() {
  const size = (id: string) => flow.findNode(id)?.dimensions ?? { width: 400, height: 300 };
  const side = cv.cards.filter((c) => c.kind === "doc" || c.kind === "memo");
  const tables = cv.cards.filter((c) => c.kind === "table" || c.kind === "dash"), cmps = cv.cards.filter((c) => c.kind === "compare" || (c.kind === "chart" && !c.compareId));
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
  ex.memos.forEach((m, i) => (key[`memo:${i}`] = cv.addMemo(m.text, { x: 0, y: 0 }, m.author)));
  for (const [s, t] of ex.links) if (key[s] && key[t]) cv.addLink(key[s], key[t]);
  setTimeout(() => same() && arrange(), 500); // ponytail: 이벤트 전송(150ms)과 비교 결과 렌더 뒤 배치하도록 고정 지연
}
// 휴지통 메뉴: 전체 지우기 / 근거 부족 지우기(최신 분석에서 근거 부족(bad)으로 지적한 카드만 지움)
const trashOpen = ref(false);
const flaggedIds = computed(() => Object.keys(cv.flags).filter((id) => cv.flags[id].some((f) => f.level === "bad")));
function clearAll() { trashOpen.value = false; if (cv.cards.length && confirm("캔버스의 카드를 모두 지울까요?")) cv.clear(); }
function clearFlagged() {
  trashOpen.value = false;
  const ids = flaggedIds.value; if (!ids.length) return;
  if (confirm(`AI가 근거 부족으로 지적한 카드 ${ids.length}장을 지울까요?`)) { for (const id of ids) cv.remove(id); clearSel(); }
}

const zoom = computed(() => Math.round(flow.viewport.value.zoom * 100));
// 트랙패드 핀치(ctrl+wheel)는 어디서든 브라우저 화면 확대를 막고 캔버스만 확대. 빈 캔버스는 Vue Flow가 처리하고,
// 카드의 스크롤 영역(.nowheel)은 Vue Flow가 건너뛰므로 커서 위치를 기준으로 직접 확대
function pinch(e: WheelEvent) {
  if (!e.ctrlKey) return;
  e.preventDefault();
  const t = e.target as Element, el = t.closest?.(".flow");
  if (!el || !t.closest(".nowheel")) return;
  const { x, y, zoom: z0 } = flow.viewport.value, r = el.getBoundingClientRect();
  const z = Math.min(2, Math.max(0.2, z0 * 2 ** (-e.deltaY * 0.02))), px = e.clientX - r.left, py = e.clientY - r.top;
  flow.setViewport({ x: px - (px - x) * z / z0, y: py - (py - y) * z / z0, zoom: z });
}
// Safari 핀치(gesture 이벤트): 캔버스 밖에서만 막음. 캔버스 안은 통과시켜야 Safari 15+가 ctrl+wheel을 보내 위 pinch가 캔버스만 확대
// ponytail: gesture만 보내는 Safari 14 이하는 캔버스 안 핀치가 화면을 확대함, 지원이 필요하면 gesturechange의 scale로 직접 확대
const noGesture = (e: Event) => { if (!(e.target as Element).closest?.(".flow")) e.preventDefault(); };
const typing = () => !!(document.activeElement as HTMLElement | null)?.closest("input,textarea,select,[contenteditable]");

// ---- 서버 모드: 다른 사람 커서와 접속자 (docs/backend/api-spec.md 5.6) ----
const realtime = SYNC_MODE === "server";
/** 다른 사람 커서를 화면 좌표로 바꾼다(캔버스 좌표 × 확대 + 이동). 사람마다 색을 고정한다. */
const remoteCursors = computed(() => {
  const { x: vx, y: vy, zoom: z } = flow.viewport.value;
  return Object.entries(cv.cursors).map(([id, c]) => ({
    id, left: c.x * z + vx, top: c.y * z + vy, color: `hsl(${(Number(id) * 67) % 360} 70% 45%)`, label: cv.nameOf(Number(id)),
  }));
});
/** 내 마우스 위치를 캔버스 좌표로 바꿔 보낸다(보내는 빈도는 연결 쪽에서 50ms로 제한). */
function onPointerMove(e: MouseEvent) {
  if (!realtime) return;
  const p = flow.screenToFlowCoordinate({ x: e.clientX, y: e.clientY });
  cv.sendCursor(p.x, p.y);
}
const key = (e: KeyboardEvent) => {
  if (!cv.canvasId) return;  // 홈에서는 페이지 단축키 없음
  // ⌘K: 오른쪽 사이드바(자료 검색) 열기 · 닫기
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k" && !cv.readOnly) {
    e.preventDefault();
    if (view.value === "canvas" && spOpen.value) spOpen.value = false; else { view.value = "canvas"; openSearch(); }
  }
  if (e.key === "Escape" && !typing()) { if (infoOpen.value) infoOpen.value = false; else clearSel(); }
};
// 다른 페이지(세션)를 열면 화면을 그 페이지에 맞춘다
watch(() => cv.canvasId, () => { clearSel(); infoOpen.value = false; view.value = "canvas"; run.value = 0; flagGo.value = null; spOpen.value = false;
  setTimeout(() => flow.fitView({ padding: 0.1, duration: 300 }), 300); });
// 캔버스 화면으로 돌아오면 Vue Flow 크기를 다시 맞춤 (숨겨져 있는 동안 크기가 0)
watch(view, (v) => { if (v === "canvas") nextTick(() => { fitSp(); flow.updateNodeInternals(); }); });
watch(() => cv.canvasId, () => nextTick(fitSp));
// 열어 둔 페이지에 대한 권한이 사라지면(정보 창에서 내 권한 삭제 등) 홈으로
watch(() => !realtime && !!cv.canvasId && !cv.meta, (lost) => { if (lost) { infoOpen.value = false; cv.goHome(); } });
onMounted(() => { nextTick(fitSp); cv.restoreUser(); window.addEventListener("keydown", key);
  window.addEventListener("wheel", pinch, { passive: false, capture: true });
  document.addEventListener("gesturestart", noGesture); document.addEventListener("gesturechange", noGesture); (window as unknown as Record<string, unknown>).__aiSelfCheck = () => selfCheck(api.analysis); });
onUnmounted(() => { window.removeEventListener("keydown", key); window.removeEventListener("wheel", pinch, { capture: true });
  document.removeEventListener("gesturestart", noGesture); document.removeEventListener("gesturechange", noGesture); });
const staleDot = computed(() => !!cv.latest && cv.latest.sig !== cv.sig);
</script>

<template>
  <div v-if="!cv.user && cv.restoring" class="auth-restoring" aria-busy="true" aria-label="로그인 정보를 확인하는 중" />
  <LoginView v-else-if="!cv.user" />
  <template v-else>
  <HomeView v-if="!cv.canvasId" />
  <template v-else>
  <div class="bar">
    <h1 class="logo"><button class="home" title="홈 (모든 페이지)" aria-label="홈으로" @click="cv.goHome()"><Logo /></button></h1>
    <input v-if="renaming" class="ctitle" :value="cv.canvasName" aria-label="페이지 이름" v-focus
      @keydown.enter="($event.target as HTMLInputElement).blur()" @blur="cv.rename(($event.target as HTMLInputElement).value); renaming = false" />
    <b v-else class="ctitle" :title="cv.readOnly ? cv.canvasName : '두 번 눌러 이름 바꾸기'" @dblclick="renaming = !cv.readOnly">{{ cv.canvasName }}</b>
    <span class="asof" title="기준 시점: 이 시점 이전에 발행된 자료만 예측에 씀">{{ cv.pageQuarter }}</span>
    <div class="acts">
      <span v-if="realtime && cv.present.length" class="presence" :title="cv.present.map((id) => cv.nameOf(id)).join(', ')">
        접속 {{ cv.present.length }}명</span>
      <span v-if="cv.readOnly" class="ro-note">보기 전용</span>
      <!-- 화면 전환: 캔버스(사용자) / 분석(AI). 정보 아이콘 옆 -->
      <div class="vsw" role="tablist" aria-label="페이지 화면">
        <button role="tab" :aria-selected="view === 'canvas'" @click="view = 'canvas'">
          <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3" width="6" height="6" rx="1.5" /><rect x="11.5" y="11" width="6" height="6" rx="1.5" /><path d="M8.5 6h3a2 2 0 0 1 2 2v3" /></svg>캔버스</button>
        <button role="tab" :aria-selected="view === 'analysis'" @click="view = 'analysis'">
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M3 16.5h14M5.5 13V9M10 13V4.5M14.5 13V7.5" /></svg>분석<i v-if="staleDot" class="sdot" title="캔버스가 바뀜" /></button>
      </div>
      <button class="ibtn" :aria-pressed="infoOpen" title="페이지 정보 · 협업자" aria-label="페이지 정보" @click="infoOpen = !infoOpen">
        <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="10" cy="10" r="7.5" /><path d="M10 9v5M10 6.2v.1" /></svg></button>
    </div>
  </div>
  <div class="main">

    <!-- 캔버스 화면 (숨겨도 Vue Flow 상태 유지) -->
    <div v-show="view === 'canvas'" ref="cview" class="cview">
      <LeftPanel v-if="lpOpen" @focus="focusCard" @link="focusLink" @close="lpOpen = false" />
      <div class="flow-wrap" :class="{ ro: cv.readOnly, spc: !spOpen }" :style="{ '--iz': Math.min(4, 100 / zoom) }" @dragover.prevent @drop.prevent="onDrop" @mousemove="onPointerMove">
        <!-- 사이드바가 닫혀 있을 때만 헤더 아래 플로팅 버튼. 열려 있으면 접기 버튼은 사이드바 안에 -->
        <button v-if="!lpOpen" class="float fl" data-tip="근거 데이터 · 메모 보기" aria-label="왼쪽 사이드바 보기" @click="lpOpen = true">
          <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M7.5 3.5v13" /></svg></button>
        <button v-if="!spOpen" class="float fr" data-tip="자료 검색 보기" aria-label="오른쪽 사이드바 보기" @click="openSearch">
          <svg viewBox="0 0 20 20" aria-hidden="true"><rect x="2.5" y="3.5" width="15" height="13" rx="2" /><path d="M12.5 3.5v13" /></svg></button>
        <!-- 빈 캔버스 안내(가운데 흰 안내창): 사이드바가 닫혀 있으면 자료 추가 버튼, 열려 있으면 끌어다 올리라는 문구 -->
        <div v-if="!cv.cards.length && cv.canEdit" class="canvas-empty"><div class="cebox">
          <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 13V3M6 7l4-4 4 4M4 13v3h12v-3" /></svg>
          <p>{{ spOpen ? "자료를 캔버스로 끌어다 올리세요" : "자료를 추가하세요" }}</p>
          <button v-if="!spOpen" class="btn fill" @click="openSearch">자료 추가</button>
        </div></div>
        <VueFlow id="canvas" class="flow" :nodes-draggable="!cv.readOnly" :nodes-connectable="!cv.readOnly" :connection-mode="ConnectionMode.Loose" :nodes="nodes" :edges="edges" :min-zoom="0.2" :max-zoom="2" :default-edge-options="{ type: 'default' }" :delete-key-code="null"
          :elements-selectable="false" :selection-key-code="null" :multi-selection-key-code="null"
          @node-drag-stop="onDragStop" @edge-click="onEdgeClick" @node-click="onNodeClick" @pane-click="clearSel">
          <template #node-table="p"><TableNode v-bind="p" /></template>
          <template #node-compare="p"><CompareNode v-bind="p" /></template>
          <template #node-doc="p"><DocNode v-bind="p" /></template>
          <template #node-memo="p"><MemoNode v-bind="p" /></template>
          <template #node-dash="p"><DashNode v-bind="p" /></template>
          <template #node-chart="p"><ChartNode v-bind="p" /></template>
          <template #edge-link="p"><LinkEdge v-bind="p" /></template>
          <Panel v-if="cv.error" position="top-center" class="linkbar err-bar" role="alert">
            <span>{{ cv.error }}</span><button @click="cv.error = ''">닫기</button>
          </Panel>
          <!-- Shift로 두 장 이상 고르면: 표 생성 · 시각화 생성 -->
          <Panel v-else-if="cv.selected.length > 1 && cv.canEdit" position="top-center" class="selbar">
            <span>{{ cv.selected.length }}장 선택<small v-if="pickedTables.length < 2 && !pickedMemo"> · 공시 표를 2장 이상 고르세요</small></span>
            <button v-if="pickedMemo" class="memo" title="고른 메모를 함께 고른 카드 모두에 선으로 이음" @click="memoToAll">메모 연결</button>
            <button class="primary" :disabled="pickedTables.length < 2" title="고른 공시 표로 비교표를 만듦 (계정이 안 겹치면 표 그대로)" @click="makeTable">표 생성</button>
            <button class="primary" :disabled="pickedTables.length < 2" title="고른 공시 표로 계정 비교 막대그래프를 만듦" @click="makeChart">시각화 생성</button>
            <button aria-label="선택 해제" @click="clearSel">✕</button>
          </Panel>
          <!-- 연결 바: 어느 카드에서 어느 카드로인지 굵게, 연결 해제 · 메모 입력은 같은 줄, 메모 입력을 누르면 아래에 메모 칸 -->
          <Panel v-else-if="link" position="top-center" class="linkbar">
            <div class="lhead"><b class="lfrom">{{ cv.cardTitle(link.source) }}</b><span class="arr" aria-label="에서">→</span><b class="lto">{{ cv.cardTitle(link.target) }}</b>
              <button class="lx" aria-label="닫기" @click="selLink = null">✕</button></div>
            <div v-if="cv.canEdit" class="lacts">
              <button @click="cv.removeLink(link.id); selLink = null">연결 해제</button>
              <button :aria-pressed="linkMemo" @click="linkMemo = !linkMemo">{{ link.memo ? "메모 보기" : "메모 입력" }}</button>
            </div>
            <div v-if="linkMemo || (cv.readOnly && link.memo)" class="lmemo">
              <MemoEditor :key="link.id" :text="link.memo" :readonly="cv.readOnly" autofocus placeholder="메모" @save="(t) => cv.setLinkMemo(link!.id, t)" /></div>
          </Panel>
          <!-- 자동 배치 · 비우기는 왼쪽 아래, 표시 단위는 오른쪽 위 그대로 -->
          <Panel v-if="!cv.readOnly" position="bottom-left" class="tools">
            <button :disabled="!cv.cards.length" @click="arrange">자동 배치</button>
            <div class="trash">
              <button class="tbtn" :disabled="!cv.cards.length" :aria-expanded="trashOpen" data-tip="지우기" aria-label="지우기" @click="trashOpen = !trashOpen">
                <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 6h12M8 6V4.5h4V6M6 6l.8 10h6.4L14 6M8.5 9v4.5M11.5 9v4.5" /></svg></button>
              <div v-if="trashOpen" class="tmenu" role="menu">
                <button role="menuitem" @click="clearAll">전체 지우기<small>카드 {{ cv.cards.length }}장</small></button>
                <button role="menuitem" :disabled="!flaggedIds.length" @click="clearFlagged">근거 부족 지우기<small>{{ flaggedIds.length ? `근거 부족 카드 ${flaggedIds.length}장` : "지적한 카드 없음" }}</small></button>
              </div>
            </div>
          </Panel>
          <Panel position="top-right" class="tools">
            <div class="seg" role="group" aria-label="표시 단위">
              <button :aria-pressed="cv.unit === 'raw'" @click="cv.unit = 'raw'">원본 단위</button>
              <button :aria-pressed="cv.unit === 'bil'" @click="cv.unit = 'bil'">십억원</button>
            </div>
          </Panel>
          <!-- 하단 도구 (피그마처럼): 자료 추가 · 메모 · 분석하기 -->
          <!-- 하단 도구: 아이콘만, 마우스를 올리면 설명 -->
          <Panel position="bottom-center" class="dockbar">
            <button v-if="!cv.readOnly" data-tip="자료 추가 (⌘K)" aria-label="자료 추가" @click="openSearch">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 4v12M4 10h12" /></svg></button>
            <button v-if="!cv.readOnly" data-tip="메모 카드 추가" aria-label="메모 카드 추가" @click="addMemo">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M4 3.5h12v9l-4 4H4z" /><path d="M12 16.5v-4h4" /></svg></button>
            <button class="primary" :disabled="!cv.cards.length" :data-tip="cv.cards.length ? '분석하기 (검토 · 질문 · 평가)' : '카드를 올린 뒤 분석할 수 있어요'" aria-label="분석하기" @click="analyze">
              <svg viewBox="0 0 20 20" aria-hidden="true"><path d="M10 2.5l1.8 4.4 4.7.4-3.6 3 1.1 4.6L10 12.4l-4 2.5 1.1-4.6-3.6-3 4.7-.4z" /></svg></button>
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
      <div v-if="spOpen" class="sresize" role="separator" aria-orientation="vertical" aria-label="자료 검색 폭 조정" title="끌어서 폭 조정" @pointerdown.prevent="resizeSp" />
      <SourcePanel :open="spOpen" :style="spW ? { width: spW + 'px' } : undefined" @close="spOpen = false" @add="(items) => place(items, center(300, 200))" @example="loadExample()" />
    </div>

    <!-- 분석 화면: 캔버스가 바뀌면 key로 새로 만들어 다른 페이지 결과가 섞이지 않게 -->
    <AnalysisView v-show="view === 'analysis'" :key="cv.canvasId" :run="run" :flag="flagGo" @cite="focusCite" @card="focusCard" @analyze="analyze" />
  </div>
  <SourceOverlay :table-id="cv.overlay.tableId" :match="cv.overlay.match" :card-id="cv.overlay.cardId"
    @close="cv.openSource(null)" @add="onAdd" />
  <PageInfo v-if="infoOpen" @close="infoOpen = false" />
  </template>
  <NewCanvasDialog v-if="cv.creating" />
  </template>
</template>
