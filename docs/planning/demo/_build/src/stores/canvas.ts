// 캔버스 상태 + 이벤트 큐. 화면은 로컬 상태로 즉시 갱신하고, 이벤트는 묶어서 서버로 보낸다.
// 서버가 이벤트 로그의 원본(source of truth)이며, 컨텍스트 층 해시를 돌려준다.
import { defineStore } from "pinia";
import { api, type CanvasEvent } from "../api";

export interface TableCard { id: string; kind: "table"; tableId: string; rows: number[]; cols: number[];
  vcol: number; edits: Record<string, number | null>; sort: { key: string | number; desc: boolean } | null;
  checked: boolean; x: number; y: number }
export interface CompareCard { id: string; kind: "compare"; sources: string[]; x: number; y: number; version: number }
export interface DocCard { id: string; kind: "doc"; docId: string; x: number; y: number }
// 메모 종류(F-07): 일반 메모 / 보완 요청. type이 없는 이전 저장본은 일반 메모
export interface MemoCard { id: string; kind: "memo"; author: string; text: string; type?: "memo" | "request"; x: number; y: number }
// 시각화 카드: 비교 카드 결과를 막대그래프로. 화면에만 있고 서버 이벤트는 없음 (ponytail: 컨텍스트에 안 들어감, 필요하면 add_chart 이벤트)
export interface ChartCard { id: string; kind: "chart"; compareId: string; x: number; y: number }
export type Card = TableCard | CompareCard | DocCard | MemoCard | ChartCard;
export interface Link { id: string; source: string; target: string; rel: string }
export const RELS = ["근거", "설명", "반박", "관련"];
// 팀 공유 목업: 같은 캔버스를 보는 팀원. 실제 서비스에서는 로그인 사용자와 이벤트 로그의 작성자
// 로그인: 사용자를 고르면 그 사람의 부서 캔버스를 같은 부서 사람들과 함께 보고 고친다 (목업. 실제 서비스는 사내 SSO)
// 부서는 기획서 1장의 사업 조직
export const DEPTS = ["네트워크센터", "AIDC사업본부", "세일즈마케팅본부", "프로덕트브랜드본부"];
export const ALL_MEMBERS = [
  { name: "이재무", dept: "네트워크센터", job: "재무기획" }, { name: "박망", dept: "네트워크센터", job: "네트워크 기술" },
  { name: "김센터", dept: "네트워크센터", job: "부서장" },
  { name: "최예산", dept: "AIDC사업본부", job: "재무기획" }, { name: "정데이터", dept: "AIDC사업본부", job: "AIDC 사업" },
  { name: "한본부", dept: "AIDC사업본부", job: "부서장" },
  { name: "오마케", dept: "세일즈마케팅본부", job: "재무기획" }, { name: "윤영업", dept: "세일즈마케팅본부", job: "세일즈" },
  { name: "서기획", dept: "프로덕트브랜드본부", job: "재무기획" }, { name: "강상품", dept: "프로덕트브랜드본부", job: "상품" }];
export const memberOf = (name: string) => ALL_MEMBERS.find((m) => m.name === name);
export const who = (name: string) => { const m = memberOf(name); return m ? `${m.name} · ${m.dept} · ${m.job}` : name; };

// 캔버스(세션) 저장: 카드·연결 스냅숏 + 서버로 보낸 이벤트 로그. 다시 열면 이벤트를 새 세션에 재생한다
// ponytail: localStorage(같은 브라우저 안에서만 공유). 실제 서비스는 서버 이벤트 저장소
export interface CanvasMeta { id: string; name: string; dept: string; owner: string; updated: string; updatedBy: string; count: number }
interface Saved { meta: CanvasMeta; cards: Card[]; links: Link[]; seq: number; activity: { author: string; text: string; time: string }[]; log: CanvasEvent[] }
const KEY = "telescope-canvas:v1";
const readAll = (): Record<string, Saved> => { try { return JSON.parse(localStorage.getItem(KEY) ?? "{}"); } catch { return {}; } };
const writeAll = (all: Record<string, Saved>) => { try { localStorage.setItem(KEY, JSON.stringify(all)); } catch { /* 저장 불가(사생활 보호 모드 등): 화면에서만 유지 */ } };
const now = () => new Date().toISOString();
const ACT: Record<string, string> = { add_card: "공시 표를 올림", add_doc: "자료를 올림", add_memo: "메모를 남김", set_memo_type: "메모 종류를 바꿈", edit_memo: "메모를 고침",
  create_compare: "비교 카드를 만듦", add_link: "카드를 연결함", set_link: "연결 관계를 바꿈", remove_link: "연결을 지움",
  remove_card: "카드를 지움", edit_cell: "값을 수정함", confirm_mapping: "계정 대응을 확정함" };

export const useCanvas = defineStore("canvas", {
  state: () => ({
    sessionId: crypto.randomUUID(),
    cards: [] as Card[],
    queue: [] as CanvasEvent[],
    seq: 0,
    lastChangedLayer: null as string | null,
    timer: 0 as number,
    unit: "raw" as "raw" | "bil",  // 표시 단위만 바꿈 (이벤트 아님)
    overlay: { tableId: null as string | null, match: [] as number[], cardId: null as string | null },
    links: [] as Link[],
    tags: {} as Record<string, string[]>,  // 카드별 개념 태그 (자동 연결용, 서버 계산)
    author: ALL_MEMBERS[0].name,
    activity: [] as { author: string; text: string; time: string }[],
    focus: null as string | null,  // 근거 ID를 눌러 강조한 카드
    user: null as { name: string; dept: string } | null,
    canvasId: "", canvasName: "",
    log: [] as CanvasEvent[],  // 이 캔버스에서 서버로 보낸 이벤트 전체 (저장·재생용)
    saved: 0,  // 저장할 때마다 올려 목록을 다시 읽게 함
  }),
  getters: {
    members: (s) => ALL_MEMBERS.filter((m) => m.dept === s.user?.dept),
    canvases: (s): CanvasMeta[] => { void s.saved; return Object.values(readAll()).map((x) => x.meta).filter((m) => m.dept === s.user?.dept)
      .sort((a, b) => b.updated.localeCompare(a.updated)); },
    checked: (s) => s.cards.filter((c): c is TableCard => c.kind === "table" && c.checked),
  },
  actions: {
    emit(e: CanvasEvent) {
      this.queue.push({ author: this.author, ...e });  // 예시 메모처럼 작성자를 지정한 이벤트는 그대로
      if (ACT[e.type]) this.activity.unshift({ author: this.author, text: ACT[e.type] + (e.card_id ? ` (${e.card_id})` : ""),
        time: new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) });
      clearTimeout(this.timer);
      this.timer = window.setTimeout(() => this.flush(), 150); // 드래그 등 연속 조작은 묶어서 전송
    },
    async flush() {
      if (!this.queue.length) return;
      const batch = this.queue.splice(0);
      this.log.push(...batch.filter((e) => e.type !== "move_card"));
      const res = await api.postEvents(this.sessionId, batch);
      this.lastChangedLayer = res.first_changed_layer;
      this.tags = await api.cardTags(this.sessionId);
      // 비교 카드는 서버 계산 결과를 다시 받아야 하므로 버전을 올린다
      for (const c of this.cards) if (c.kind === "compare") c.version++;
      // 카드 구성이 바뀌었으면 다음 질문에 대비해 prefix를 미리 채운다
      if (res.first_changed_layer && res.first_changed_layer <= "L2_workspace") api.warmup(this.sessionId).catch(() => {});
      this.save();
    },
    login(name: string) {
      const m = memberOf(name); if (!m) return;
      this.user = { name, dept: m.dept }; this.author = name;
      try { sessionStorage.setItem(KEY + ":user", JSON.stringify(this.user)); } catch { /* 무시 */ }
      const first = this.canvases[0];
      return first ? this.open(first.id) : this.newCanvas();
    },
    logout() { this.save(); this.user = null; try { sessionStorage.removeItem(KEY + ":user"); } catch { /* 무시 */ } },
    restoreUser() { try { const u = JSON.parse(sessionStorage.getItem(KEY + ":user") ?? "null"); if (u) return this.login(u.name); } catch { /* 무시 */ } },
    save() {
      if (!this.canvasId || !this.user) return;
      const all = readAll(), prev = all[this.canvasId]?.meta;
      all[this.canvasId] = { meta: { id: this.canvasId, name: this.canvasName, dept: prev?.dept ?? this.user.dept, owner: prev?.owner ?? this.author,
        updated: now(), updatedBy: this.author, count: this.cards.length },
        cards: JSON.parse(JSON.stringify(this.cards)), links: this.links, seq: this.seq, activity: this.activity.slice(0, 200), log: this.log };
      writeAll(all); this.saved++;
    },
    reset() {
      clearTimeout(this.timer); this.queue = [];
      Object.assign(this, { sessionId: crypto.randomUUID(), cards: [], links: [], tags: {}, activity: [], log: [], seq: 0, lastChangedLayer: null, focus: null });
      this.overlay = { tableId: null, match: [], cardId: null };
    },
    async newCanvas(name?: string) {
      name ??= `${this.user?.dept ?? ""} 캔버스 ${this.canvases.length + 1}`;
      await this.flush(); this.save(); this.reset();
      this.canvasId = "cv" + Date.now().toString(36); this.canvasName = name;
      this.save();
    },
    async open(id: string) {
      if (id === this.canvasId) return;
      await this.flush(); this.save();
      const x = readAll()[id]; if (!x) return;
      this.reset();
      this.canvasId = id; this.canvasName = x.meta.name;
      Object.assign(this, { cards: x.cards, links: x.links, seq: x.seq, activity: x.activity, log: x.log });
      if (x.log.length) await api.postEvents(this.sessionId, x.log);  // 새 세션에 이벤트 재생 → 서버 상태 복원
      this.tags = await api.cardTags(this.sessionId);
      for (const c of this.cards) if (c.kind === "compare") c.version++;
    },
    rename(name: string) { this.canvasName = name.trim() || "제목 없는 캔버스"; this.save(); },
    removeCanvas(id: string) {
      const all = readAll(); delete all[id]; writeAll(all); this.saved++;
      if (id === this.canvasId) { this.canvasId = ""; const next = this.canvases[0]; next ? this.open(next.id) : this.newCanvas(); }
    },
    newId() { return "c" + ++this.seq; },
    addTableCard(tableId: string, rows: number[], cols: number[], vcol = cols[0], pos = { x: 0, y: 0 }) {
      const id = this.newId();
      this.cards.push({ id, kind: "table", tableId, rows, cols, vcol, edits: {}, sort: null, checked: false, ...pos });
      this.emit({ type: "add_card", card_id: id, table_id: tableId, rows, cols });
      if (vcol !== cols[0]) this.emit({ type: "select_period", card_id: id, c: vcol });
      return id;
    },
    addRows(id: string, rows: number[]) {
      const c = this.cards.find((x) => x.id === id) as TableCard;
      c.rows = [...new Set([...c.rows, ...rows])].sort((a, b) => a - b);
      this.emit({ type: "add_rows", card_id: id, rows });
    },
    openSource(tableId: string | null, match: number[] = [], cardId: string | null = null) {
      this.overlay = { tableId, match, cardId };
    },
    setRows(id: string, rows: number[]) {  // 원본 오버레이에서 기존 카드의 행 구성을 바꿀 때
      const c = this.cards.find((x) => x.id === id) as TableCard;
      const add = rows.filter((r) => !c.rows.includes(r)), del = c.rows.filter((r) => !rows.includes(r));
      if (add.length) this.addRows(id, add);
      del.forEach((r) => this.removeRow(id, r));
    },
    clear() { [...this.cards].forEach((c) => this.remove(c.id)); },
    removeRow(id: string, r: number) {
      const c = this.cards.find((x) => x.id === id) as TableCard;
      c.rows = c.rows.filter((x) => x !== r);
      this.emit({ type: "remove_row", card_id: id, r });
    },
    editCell(id: string, r: number, c: number, value: number | null) {
      const card = this.cards.find((x) => x.id === id) as TableCard;
      card.edits[`${r},${c}`] = value;
      this.emit({ type: "edit_cell", card_id: id, r, c, value });
    },
    setPeriod(id: string, c: number) {
      (this.cards.find((x) => x.id === id) as TableCard).vcol = c;
      this.emit({ type: "select_period", card_id: id, c });
    },
    sortCard(id: string, key: string | number | null, desc = false) {
      (this.cards.find((x) => x.id === id) as TableCard).sort = key === null ? null : { key, desc };
      this.emit({ type: "sort_card", card_id: id, key, desc });
    },
    move(id: string, x: number, y: number) {
      const c = this.cards.find((k) => k.id === id); if (!c) return;
      c.x = x; c.y = y;
      this.emit({ type: "move_card", card_id: id, x, y }); // 컨텍스트에는 영향 없음 (서버에서 무시)
    },
    addDocCard(docId: string, pos = { x: 0, y: 0 }) {
      const id = this.newId();
      this.cards.push({ id, kind: "doc", docId, ...pos });
      this.emit({ type: "add_doc", card_id: id, doc_id: docId });
      return id;
    },
    addMemo(text: string, pos = { x: 0, y: 0 }, author?: string, type: "memo" | "request" = "memo") {
      author ??= this.author;
      const id = this.newId();
      this.cards.push({ id, kind: "memo", author, text, type, ...pos });
      this.emit({ type: "add_memo", card_id: id, text, author, memo_type: type });
      return id;
    },
    setMemoType(id: string, type: "memo" | "request") {
      (this.cards.find((c) => c.id === id) as MemoCard).type = type;
      this.emit({ type: "set_memo_type", card_id: id, memo_type: type });
    },
    editMemo(id: string, text: string) {
      (this.cards.find((c) => c.id === id) as MemoCard).text = text;
      this.emit({ type: "edit_memo", card_id: id, text });
    },
    addLink(source: string, target: string, rel = RELS[0]) {
      if (source === target || this.links.some((l) => l.source === source && l.target === target)) return;
      const id = "l" + ++this.seq;
      this.links.push({ id, source, target, rel });
      this.emit({ type: "add_link", link_id: id, source, target, rel });
    },
    setLink(id: string, rel: string) {
      this.links.find((l) => l.id === id)!.rel = rel;
      this.emit({ type: "set_link", link_id: id, rel });
    },
    removeLink(id: string) {
      this.links = this.links.filter((l) => l.id !== id);
      this.emit({ type: "remove_link", link_id: id });
    },
    addChart(compareId: string) {
      const c = this.cards.find((x) => x.id === compareId)!, id = this.newId();
      this.cards.push({ id, kind: "chart", compareId, x: c.x, y: c.y + 520 });
      this.save();
      return id;
    },
    remove(id: string) {
      if (this.cards.find((c) => c.id === id)?.kind === "chart") { this.cards = this.cards.filter((c) => c.id !== id); this.save(); return; }
      this.cards.filter((c) => c.kind === "chart" && c.compareId === id).forEach((c) => this.remove(c.id));
      this.links = this.links.filter((l) => l.source !== id && l.target !== id);
      this.cards = this.cards.filter((c) => c.id !== id);
      for (const c of this.cards) if (c.kind === "compare") c.sources = c.sources.filter((s) => s !== id);
      this.emit({ type: "remove_card", card_id: id });
    },
    createCompare() {
      const src = this.checked; if (src.length < 2) return null;
      const id = this.newId();
      const x = Math.max(...src.map((c) => c.x)) + 620, y = Math.min(...src.map((c) => c.y));
      this.cards.push({ id, kind: "compare", sources: src.map((c) => c.id), x, y, version: 0 });
      src.forEach((c) => (c.checked = false));
      this.emit({ type: "create_compare", card_id: id, sources: src.map((c) => c.id) });
      return id;
    },
    confirmMapping(conceptId: string, company: string, cells: string[], accepted: boolean) {
      this.emit({ type: "confirm_mapping", concept_id: conceptId, company, cells, accepted });
    },
  },
});
