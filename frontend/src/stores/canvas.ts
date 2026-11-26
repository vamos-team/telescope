// 캔버스 상태 + 이벤트 큐. 화면은 로컬 상태로 즉시 갱신하고, 이벤트는 묶어서 서버로 보낸다.
// 서버가 이벤트 로그의 원본(source of truth)이며, 컨텍스트 층 해시를 돌려준다.
import { defineStore } from "pinia";
import { api, docTitle, type CanvasEvent, type Doc } from "../api";
import { panelOf } from "../local/dashboard";
import type { Session } from "../local/ai";

// memo: 카드 메모 (카드에 직접 적은 메모). 메모는 한 종류이고, 지시 · 근거 · 의견은 분석(검토)에서 에이전트가 가린다
export interface TableCard { id: string; kind: "table"; tableId: string; rows: number[]; cols: number[];
  vcol: number; edits: Record<string, number | null>; sort: { key: string | number; desc: boolean } | null;
  checked: boolean; x: number; y: number; memo?: string }
export interface CompareCard { id: string; kind: "compare"; sources: string[]; x: number; y: number; version: number; memo?: string }
export interface DocCard { id: string; kind: "doc"; docId: string; x: number; y: number; memo?: string }
// 메모 카드 (F-07): 다른 카드에 선으로 이어 다는 메모. 카드 메모 칸은 없음
export interface MemoCard { id: string; kind: "memo"; author: string; text: string; x: number; y: number; memo?: string }
// 시각화 카드: 고른 표 카드(sources)의 계정 대응 결과를 막대그래프로. 서버에는 비교 카드와 같은 계산(create_compare, view chart)으로 등록
export interface ChartCard { id: string; kind: "chart"; sources: string[]; compareId?: string; version: number; x: number; y: number; memo?: string }
// 대시보드 카드: 자료 검색 대시보드 패널을 캔버스로 가져온 것 (panel = DASH_PANELS id)
export interface DashCard { id: string; kind: "dash"; panel: string; x: number; y: number; memo?: string }
export type Card = TableCard | CompareCard | DocCard | MemoCard | ChartCard | DashCard;
// 선: 관계 태그 없음(에이전트가 판단). memo = 선 메모. rel은 예전 저장본 호환용
export interface Link { id: string; source: string; target: string; rel?: string; memo?: string; memoBy?: string }  // memoBy: 선 메모를 단 사람
// 메모 3종 (카드 메모 · 메모 카드 · 선 메모). 왼쪽 메모 목록과 분석 입력에 씀
export interface Note { key: string; kind: "card" | "memo" | "link"; cardId?: string; linkId?: string; where: string; text: string; author?: string }
// 데이터 성격(강조색): dart 공시 / ir / news / internal / viz(비교·시각화·대시보드) / memo
// 팀 공유 목업: 같은 캔버스를 보는 팀원. 실제 서비스에서는 로그인 사용자와 이벤트 로그의 작성자
// 로그인: 사번 + 비밀번호(목업이라 아무 값이나 통과). 들어가면 캔버스 홈에서 볼 수 있는 캔버스를 고른다 (실제 서비스는 사내 SSO)
// 부서는 기획서 1장의 사업 조직
export const DEPTS = ["네트워크센터", "AIDC사업본부", "세일즈마케팅본부", "프로덕트브랜드본부"];
export const ALL_MEMBERS = [
  { id: "1001", name: "이재무", dept: "네트워크센터", job: "재무기획" }, { id: "1002", name: "박망", dept: "네트워크센터", job: "네트워크 기술" },
  { id: "1003", name: "김센터", dept: "네트워크센터", job: "부서장" },
  { id: "2001", name: "최예산", dept: "AIDC사업본부", job: "재무기획" }, { id: "2002", name: "정데이터", dept: "AIDC사업본부", job: "AIDC 사업" },
  { id: "2003", name: "한본부", dept: "AIDC사업본부", job: "부서장" },
  { id: "3001", name: "오마케", dept: "세일즈마케팅본부", job: "재무기획" }, { id: "3002", name: "윤영업", dept: "세일즈마케팅본부", job: "세일즈" },
  { id: "4001", name: "서기획", dept: "프로덕트브랜드본부", job: "재무기획" }, { id: "4002", name: "강상품", dept: "프로덕트브랜드본부", job: "상품" }];
export const memberOf = (name: string) => ALL_MEMBERS.find((m) => m.name === name);
export const memberById = (id: string) => ALL_MEMBERS.find((m) => m.id === id.trim());
export const who = (name: string) => { const m = memberOf(name); return m ? `${m.name} · ${m.dept} · ${m.job}` : name; };

// 캔버스(세션) 저장: 카드·연결 스냅숏 + 서버로 보낸 이벤트 로그. 다시 열면 이벤트를 새 세션에 재생한다
// ponytail: localStorage(같은 브라우저 안에서만 공유). 실제 서비스는 서버 이벤트 저장소
// 공유(F-08 권한 목업): 소유자, 초대한 사람별 권한, 일반 액세스(초대한 사람만 / 회사 전체 보기)
export type Role = "owner" | "edit" | "view";
export type Access = "invited" | "company";
export interface CanvasMeta { id: string; name: string; dept: string; owner: string; updated: string; updatedBy: string; count: number;
  ownerId?: string; share?: Record<string, "edit" | "view">; access?: Access; quarter?: string }
// 예전 저장분(공유 필드 없음, 부서 전체)은 초대한 사람만으로 읽음
export const shareOf = (m: CanvasMeta) => ({ ownerId: m.ownerId ?? memberOf(m.owner)?.id ?? "", share: m.share ?? {},
  access: (m.access === "company" ? "company" : "invited") as Access });
export const ROLE: Record<Role, string> = { owner: "소유자", edit: "편집 가능", view: "보기 가능" };
export const accessText = (m: CanvasMeta) => { const s = shareOf(m);
  return s.access === "company" ? "회사 전체 보기" : "초대한 사람만"; };
export const when = (iso: string) => {
  const d = new Date(iso), m = Math.round((Date.now() - d.getTime()) / 60000);
  return m < 1 ? "방금" : m < 60 ? `${m}분 전` : m < 1440 ? `${Math.round(m / 60)}시간 전` : d.toLocaleDateString("ko-KR");
};
// 캔버스 분기: 만들 때 고름. 예전 저장분은 기본 분기로 읽음
export const QUARTERS = ["2025년 1분기", "2025년 2분기", "2025년 3분기", "2025년 4분기", "2026년 1분기", "2026년 2분기", "2026년 3분기", "2026년 4분기"];
export const DEFAULT_QUARTER = "2026년 1분기";
export const quarterOf = (m?: CanvasMeta) => m?.quarter ?? DEFAULT_QUARTER;
// 사람 찾기 (공유 창 · 새 캔버스 창): 입력이 비면 추천(같은 부서 먼저), 입력하면 사번 · 이름 · 부서로 찾음. 최대 6명
export function findPeople(q: string, exclude: Set<string>, dept: string) {
  const t = q.trim(), rest = ALL_MEMBERS.filter((m) => !exclude.has(m.id));
  if (!t) return [...rest.filter((m) => m.dept === dept), ...rest.filter((m) => m.dept !== dept)].slice(0, 6);
  return rest.filter((m) => m.id.includes(t) || m.name.includes(t) || m.dept.includes(t)).slice(0, 6);
}
export function roleOf(m: CanvasMeta, u: { id: string; dept: string } | null): Role | null {
  if (!u) return null;
  const s = shareOf(m);
  if (s.ownerId === u.id) return "owner";
  if (s.share[u.id]) return s.share[u.id];
  if (s.access === "company") return "view";
  return null;
}
// uploads: 이 캔버스 카드가 쓰는 업로드 문서(파서 요약본). 새로고침 뒤 같은 ID로 다시 등록해 카드가 사라지지 않게 함
interface Saved { meta: CanvasMeta; cards: Card[]; links: Link[]; seq: number; activity: { author: string; text: string; time: string }[]; log: CanvasEvent[]; uploads?: Doc[] }
const KEY = "telescope-canvas:v1";
// 분석 세션: 요청한 본인만 봄(CLAUDE.md 권한). 페이지 · 사용자별로 따로 저장하고, 결과는 고치지 않고 새로 쌓는다
const AKEY = "telescope-analyses:v1";
const readA = (): Record<string, Session[]> => { try { return JSON.parse(localStorage.getItem(AKEY) ?? "{}"); } catch { return {}; } };
const writeA = (all: Record<string, Session[]>) => { try { localStorage.setItem(AKEY, JSON.stringify(all)); } catch { /* 저장 불가: 화면에서만 유지 */ } };
const readAll = (): Record<string, Saved> => { try { return JSON.parse(localStorage.getItem(KEY) ?? "{}"); } catch { return {}; } };
const writeAll = (all: Record<string, Saved>) => { try { localStorage.setItem(KEY, JSON.stringify(all)); } catch { /* 저장 불가(사생활 보호 모드 등): 화면에서만 유지 */ } };
const now = () => new Date().toISOString();
const ACT: Record<string, string> = { add_card: "공시 표를 올림", add_doc: "자료를 올림", add_memo: "메모를 남김", edit_memo: "메모를 고침", set_card_memo: "카드 메모를 고침", set_link_memo: "선 메모를 고침", add_dash: "대시보드를 올림",
  create_compare: "비교 카드를 만듦", add_link: "카드를 연결함", remove_link: "연결을 지움",
  remove_card: "카드를 지움", edit_cell: "값을 수정함", confirm_mapping: "계정 대응을 확정함" };

// 예전 저장본 읽기: 프롬프트 → 메모, 메모 종류 · 선 관계는 버림. 이벤트 로그도 같이 바꿔 재생
function migrate(x: Saved) {
  for (const c of x.cards as unknown as { kind: string; prompt?: string; type?: string; memo?: string }[]) {
    if (c.kind === "prompt") c.kind = "memo";
    if (c.prompt != null) { if (c.kind !== "memo") c.memo ??= c.prompt; delete c.prompt; }
    delete c.type;
  }
  for (const l of x.links as (Link & { prompt?: string })[]) if (l.prompt != null) { l.memo ??= l.prompt; delete l.prompt; }
  const T: Record<string, string> = { add_prompt: "add_memo", edit_prompt: "edit_memo", set_card_prompt: "set_card_memo", set_link_prompt: "set_link_memo" };
  x.log = x.log.filter((e) => e.type !== "set_memo_type" && e.type !== "set_link").map((e) => (T[e.type] ? { ...e, type: T[e.type] } : e));
  // 선 메모 작성자: 저장본에 없으면 이벤트 로그의 마지막 set_link_memo 작성자로
  for (const l of x.links) if (l.memo && !l.memoBy) l.memoBy = [...x.log].reverse().find((e) => e.type === "set_link_memo" && e.link_id === l.id)?.author as string | undefined;
}

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
    active: null as string | null,  // 누른 카드: 그 카드와 이어진 카드만 밝게, 카드 메모 칸이 펼쳐짐
    selected: [] as string[],  // Shift로 여러 장 고른 카드 (표 생성 · 시각화 생성)
    analyses: [] as Session[],  // 이 페이지에서 내가 돌린 분석 세션 (최신이 앞)
    user: null as { id: string; name: string; dept: string } | null,
    canvasId: "", canvasName: "",
    creating: false,  // 새 캔버스 창
    log: [] as CanvasEvent[],  // 이 캔버스에서 서버로 보낸 이벤트 전체 (저장·재생용)
    saved: 0,  // 저장할 때마다 올려 목록을 다시 읽게 함
    rev: 0,  // 근거에 영향을 주는 조작마다 오름 (결과 패널의 "다시 생성" 안내 기준)
    error: "",  // 이벤트 반영·캔버스 복원 실패 안내
  }),
  getters: {
    // 볼 수 있는 캔버스 전부 (소유 · 초대 · 부서 · 회사 접근)
    canvases: (s): CanvasMeta[] => { void s.saved; return Object.values(readAll()).map((x) => x.meta).filter((m) => roleOf(m, s.user))
      .sort((a, b) => b.updated.localeCompare(a.updated)); },
    meta(): CanvasMeta | undefined { return this.canvases.find((m) => m.id === this.canvasId); },
    role(): Role | null { return this.meta ? roleOf(this.meta, this.user) : null; },  // 메타가 없으면(권한이 풀림 등) 권한 없음
    readOnly(): boolean { return this.role === "view"; },
    canEdit(): boolean { return this.role === "owner" || this.role === "edit"; },  // 소유자 · 편집 권한만 편집
    // 이 캔버스에 권한 있는 사람 (활동 로그 목록, 공유 창)
    members(): (typeof ALL_MEMBERS[number] & { role: Role })[] {
      const m = this.meta; if (!m) return [];
      return ALL_MEMBERS.flatMap((p) => { const role = roleOf(m, p); return role ? [{ ...p, role }] : []; })
        .sort((a, b) => (a.role === "owner" ? -1 : b.role === "owner" ? 1 : 0));
    },
    // 카드 이름 (왼쪽 프롬프트 목록, 선 표시, 결과물의 작성 지시)
    cardTitle: (s) => (id: string) => {
      const c = s.cards.find((x) => x.id === id); if (!c) return id;
      if (c.kind === "table") { const t = api.tableInfo(c.tableId); return `${t.company} ${t.title}`; }
      if (c.kind === "doc") { const d = api.docs.get(c.docId); return d ? docTitle(d) : id; }
      if (c.kind === "dash") return panelOf(c.panel)?.title ?? "대시보드";
      if (c.kind === "memo") return `${c.author} 메모`;
      return c.kind === "compare" ? "계정 대응 비교" : "시각화";
    },
    // 메모 3종 전부: 카드 메모, 메모 카드(이어진 카드 이름), 선 메모
    notes(): Note[] {
      const linkedTo = (id: string) => this.links.filter((l) => l.source === id || l.target === id).map((l) => this.cardTitle(l.source === id ? l.target : l.source));
      return [...this.cards.flatMap((c): Note[] => c.kind !== "memo" && c.memo ? [{ key: "cm" + c.id, kind: "card", cardId: c.id, where: this.cardTitle(c.id), text: c.memo }] : []),
        ...this.cards.flatMap((c): Note[] => c.kind === "memo" && c.text.trim() ? [{ key: "mc" + c.id, kind: "memo", cardId: c.id, where: linkedTo(c.id).join(", ") || "연결 없음", text: c.text.trim(), author: c.author }] : []),
        ...this.links.flatMap((l): Note[] => l.memo ? [{ key: "lm" + l.id, kind: "link", linkId: l.id, where: `${this.cardTitle(l.source)} → ${this.cardTitle(l.target)}`, text: l.memo, author: l.memoBy }] : [])];
    },
    memos: (s) => s.cards.filter((c): c is MemoCard => c.kind === "memo" && !!c.text.trim()),
    latest: (s): Session | null => s.analyses[0] ?? null,
    // 분석에 영향을 주는 캔버스 내용의 지문 (좌표 · 화면 상태 제외). 마지막 분석과 다르면 "캔버스가 변경되었습니다"
    sig: (s) => JSON.stringify([s.cards.map((c) => { const { x: _x, y: _y, ...r } = c as Card & { version?: number; checked?: boolean };
      delete r.version; delete r.checked; return r; }), s.links.map((l) => [l.source, l.target, l.memo ?? ""])]),
    // 최신 분석의 카드별 지적 (캔버스 박스)
    flags(): Record<string, { level: "bad" | "warn"; text: string }[]> {
      const out: Record<string, { level: "bad" | "warn"; text: string }[]> = {};
      for (const f of this.latest?.review.findings ?? []) if (this.cards.some((c) => c.id === f.cardId)) (out[f.cardId] ??= []).push(f);
      return out;
    },
    checked: (s) => s.cards.filter((c): c is TableCard => c.kind === "table" && c.checked),
  },
  actions: {
    emit(e: CanvasEvent) {
      if (this.blocked()) return;  // 이벤트 단계에서도 한 번 더 막음
      this.queue.push({ author: this.author, ...e });  // 예시 메모처럼 작성자를 지정한 이벤트는 그대로
      if (e.type !== "move_card") this.rev++;
      if (ACT[e.type]) this.activity.unshift({ author: this.author, text: ACT[e.type] + (e.card_id ? ` (${e.card_id})` : ""),
        time: new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) });
      clearTimeout(this.timer);
      this.timer = window.setTimeout(() => { this.flush().catch((err) => console.error(err)); }, 150); // 드래그 등 연속 조작은 묶어서 전송
    },
    async flush() {
      if (!this.queue.length) return;
      const batch = this.queue.splice(0);
      let res;
      try {
        res = await api.postEvents(this.sessionId, batch);
      } catch (err) {  // 반영 못 한 조작은 저장 로그에 넣지 않고 큐 앞에 되돌려 다음 전송 때 다시 보냄
        console.error("이벤트 반영 실패", err);
        this.queue.unshift(...batch);
        this.error = "조작을 반영하지 못했습니다. 새로고침 후 다시 시도하세요.";
        return;
      }
      this.log.push(...batch.filter((e) => e.type !== "move_card"));  // 서버가 받은 조작만 저장·재생 로그로
      this.tags = await api.cardTags(this.sessionId).catch(() => this.tags);
      this.lastChangedLayer = res.first_changed_layer;
      // 비교 카드는 서버 계산 결과를 다시 받아야 하므로 버전을 올린다
      for (const c of this.cards) if (c.kind === "compare" || c.kind === "chart") c.version++;
      // 카드 구성이 바뀌었으면 다음 질문에 대비해 prefix를 미리 채운다
      if (res.first_changed_layer && res.first_changed_layer <= "L2_workspace") api.warmup(this.sessionId).catch(() => {});
      this.save();
    },
    login(id: string) {  // 사번으로 들어감. 없는 사번이면 false
      const m = memberById(id); if (!m) return false;
      this.user = { id: m.id, name: m.name, dept: m.dept }; this.author = m.name;
      try { sessionStorage.setItem(KEY + ":user", JSON.stringify(this.user)); } catch { /* 무시 */ }
      return this.restoreCanvas();  // 로그인하면 캔버스 홈. 새로고침이면 보던 캔버스로
    },
    restoreCanvas() {
      try { const id = sessionStorage.getItem(KEY + ":canvas"); if (id && this.canvases.some((m) => m.id === id)) return this.open(id); } catch { /* 무시 */ }
    },
    async goHome() {
      await this.flush(); this.save(); this.reset(); this.canvasId = ""; this.canvasName = "";
      try { sessionStorage.removeItem(KEY + ":canvas"); } catch { /* 무시 */ }
    },
    async logout() {  // 대기 중인 조작을 지금 사용자 이름으로 저장한 뒤 캔버스 상태를 비움 (다음 사용자가 이전 캔버스를 덮어쓰지 않게)
      await this.flush(); this.save();
      this.reset(); this.canvasId = ""; this.canvasName = ""; this.user = null;
      try { sessionStorage.removeItem(KEY + ":user"); sessionStorage.removeItem(KEY + ":canvas"); } catch { /* 무시 */ }
    },
    restoreUser() { try { const u = JSON.parse(sessionStorage.getItem(KEY + ":user") ?? "null"); if (u?.id) return this.login(u.id); } catch { /* 무시 */ } },
    save() {
      if (!this.canvasId || !this.user) return;
      const all = readAll(), prev = all[this.canvasId]?.meta;
      if (prev && !["owner", "edit"].includes(roleOf(prev, this.user) ?? "")) return;  // 저장된 캔버스는 편집 권한이 있을 때만. 처음 저장(새 캔버스)은 통과
      all[this.canvasId] = { meta: { id: this.canvasId, name: this.canvasName, dept: prev?.dept ?? this.user.dept, owner: prev?.owner ?? this.author,
        updated: now(), updatedBy: this.author, count: this.cards.length, quarter: prev?.quarter, ...(prev ? shareOf(prev) : { ownerId: this.user.id, share: {}, access: "invited" as Access }) },
        cards: JSON.parse(JSON.stringify(this.cards)), links: this.links, seq: this.seq, activity: this.activity.slice(0, 200), log: this.log,
        uploads: this.cards.flatMap((c) => { const d = c.kind === "doc" ? api.docs.get(c.docId) : undefined; return d?.uploaded ? [d] : []; }) };
      writeAll(all); this.saved++;
    },
    reset() {
      clearTimeout(this.timer); this.queue = []; this.error = "";
      Object.assign(this, { sessionId: crypto.randomUUID(), cards: [], links: [], tags: {}, activity: [], log: [], seq: 0, lastChangedLayer: null, focus: null, active: null, selected: [], analyses: [] });
      this.overlay = { tableId: null, match: [], cardId: null };
    },
    defaultName() { return `${this.user?.dept ?? ""} 페이지 ${this.canvases.filter((m) => roleOf(m, this.user) === "owner").length + 1}`; },
    // 새 캔버스: 이름, 분기, 편집할 사람을 받아 만들고 엶
    async newCanvas(opt: { name?: string; quarter?: string; editors?: string[] } = {}) {
      await this.flush(); this.save(); this.reset();
      this.canvasId = "cv" + Date.now().toString(36); this.canvasName = opt.name?.trim() || this.defaultName();
      this.save();
      const all = readAll(), x = all[this.canvasId];
      if (x) { x.meta = { ...x.meta, quarter: opt.quarter ?? DEFAULT_QUARTER, share: Object.fromEntries((opt.editors ?? []).map((id) => [id, "edit" as const])) }; writeAll(all); this.saved++; }
      try { sessionStorage.setItem(KEY + ":canvas", this.canvasId); } catch { /* 무시 */ }
    },
    async open(id: string) {
      if (id === this.canvasId) return;
      await this.flush(); this.save();
      const x = readAll()[id]; if (!x) return;
      this.reset();
      this.canvasId = id; this.canvasName = x.meta.name;
      try { sessionStorage.setItem(KEY + ":canvas", id); } catch { /* 무시 */ }
      for (const d of x.uploads ?? []) if (!api.docs.has(d.id)) api.registerDoc(d);  // 업로드 문서를 같은 ID로 다시 등록 (카드보다 먼저)
      migrate(x);
      Object.assign(this, { cards: x.cards, links: x.links, seq: x.seq, activity: x.activity, log: x.log, analyses: this.readAnalyses() });
      try {
        if (x.log.length) await api.postEvents(this.sessionId, x.log);  // 새 세션에 이벤트 재생 → 서버 상태 복원
        // 예전 시각화 카드(화면 전용, 서버 이벤트 없음)는 원본 비교 카드의 표로 서버에 등록
        const late: CanvasEvent[] = [];
        for (const c of this.cards) if (c.kind === "chart" && !x.log.some((e) => e.type === "create_compare" && e.card_id === c.id)) {
          const src = (this.cards.find((k) => k.id === c.compareId) as CompareCard | undefined)?.sources ?? c.sources ?? [];
          c.sources = src; c.version ??= 0;
          late.push({ type: "create_compare", card_id: c.id, sources: src, view: "chart", author: this.author });
        }
        if (late.length) { await api.postEvents(this.sessionId, late); this.log.push(...late); }
        this.tags = await api.cardTags(this.sessionId);
      } catch (err) {
        console.error("캔버스 복원 실패", err);
        this.error = `'${x.meta.name}' 페이지 일부를 복원하지 못했습니다. 자료가 바뀌었을 수 있습니다.`;
      }
      for (const c of this.cards) if (c.kind === "compare" || c.kind === "chart") c.version++;
    },
    rename(name: string) { if (this.blocked()) return; this.canvasName = name.trim() || "제목 없는 페이지"; this.save(); },
    removeCanvas(id: string) {  // 소유자만
      const all = readAll(); if (!all[id] || roleOf(all[id].meta, this.user) !== "owner") return;
      delete all[id]; writeAll(all); this.saved++;
      if (id === this.canvasId) { this.reset(); this.canvasId = ""; this.canvasName = ""; }
    },
    thumb(id: string) { return (readAll()[id]?.cards ?? []).map((c) => ({ kind: c.kind, x: c.x, y: c.y })); },  // 홈 미리보기
    // 공유 바꾸기: 소유자·편집 권한만. 지금 캔버스 meta를 고쳐 바로 저장
    setShare(patch: { share?: Record<string, "edit" | "view">; access?: Access }) {
      if (this.role !== "owner" && this.role !== "edit") return;
      const all = readAll(), x = all[this.canvasId]; if (!x) return;
      x.meta = { ...x.meta, ...shareOf(x.meta), ...patch }; writeAll(all); this.saved++;
      this.activity.unshift({ author: this.author, text: "공유 권한을 바꿈", time: new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) });
    },
    // 화면 상태를 바꾸기 전에 권한 확인. 편집 권한이 없으면 안내만 하고 막음 (키보드로 버튼을 눌러도 상태가 바뀌지 않게)
    blocked() { if (this.canEdit) return false; this.error = "보기 권한만 있습니다."; return true; },
    newId() { return "c" + ++this.seq; },
    addTableCard(tableId: string, rows: number[], cols: number[], vcol = cols[0], pos = { x: 0, y: 0 }) {
      if (this.blocked()) return "";
      const id = this.newId();
      this.cards.push({ id, kind: "table", tableId, rows, cols, vcol, edits: {}, sort: null, checked: false, ...pos });
      this.emit({ type: "add_card", card_id: id, table_id: tableId, rows, cols });
      if (vcol !== cols[0]) this.emit({ type: "select_period", card_id: id, c: vcol });
      return id;
    },
    addRows(id: string, rows: number[]) {
      if (this.blocked()) return;
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
      if (this.blocked()) return;
      const c = this.cards.find((x) => x.id === id) as TableCard;
      c.rows = c.rows.filter((x) => x !== r);
      this.emit({ type: "remove_row", card_id: id, r });
    },
    editCell(id: string, r: number, c: number, value: number | null) {
      if (this.blocked()) return;
      const card = this.cards.find((x) => x.id === id) as TableCard;
      card.edits[`${r},${c}`] = value;
      this.emit({ type: "edit_cell", card_id: id, r, c, value });
    },
    setPeriod(id: string, c: number) {
      if (this.blocked()) return;
      (this.cards.find((x) => x.id === id) as TableCard).vcol = c;
      this.emit({ type: "select_period", card_id: id, c });
    },
    sortCard(id: string, key: string | number | null, desc = false) {
      if (this.blocked()) return;
      (this.cards.find((x) => x.id === id) as TableCard).sort = key === null ? null : { key, desc };
      this.emit({ type: "sort_card", card_id: id, key, desc });
    },
    move(id: string, x: number, y: number) {
      if (this.blocked()) return;
      const c = this.cards.find((k) => k.id === id); if (!c) return;
      c.x = x; c.y = y;
      this.emit({ type: "move_card", card_id: id, x, y }); // 컨텍스트에는 영향 없음 (서버에서 무시)
    },
    addDocCard(docId: string, pos = { x: 0, y: 0 }) {
      if (this.blocked()) return "";
      const id = this.newId();
      this.cards.push({ id, kind: "doc", docId, ...pos });
      this.emit({ type: "add_doc", card_id: id, doc_id: docId });
      return id;
    },
    addMemo(text: string, pos = { x: 0, y: 0 }, author?: string) {
      if (this.blocked()) return "";
      author ??= this.author;
      const id = this.newId();
      this.cards.push({ id, kind: "memo", author, text, ...pos });
      this.emit({ type: "add_memo", card_id: id, text, author });
      return id;
    },
    // 카드 오른쪽 위 + : 그 카드 오른쪽에 빈 메모 카드를 만들고 선으로 잇는다
    addMemoFor(cardId: string, width = 420) {
      const c = this.cards.find((x) => x.id === cardId); if (!c || this.blocked()) return "";
      const n = this.links.filter((l) => l.target === cardId && this.cards.find((k) => k.id === l.source)?.kind === "memo").length;
      const id = this.addMemo("", { x: c.x + width + 60, y: c.y + n * 170 });
      this.addLink(id, cardId);
      return id;
    },
    editMemo(id: string, text: string) {
      if (this.blocked()) return;
      (this.cards.find((c) => c.id === id) as MemoCard).text = text;
      this.emit({ type: "edit_memo", card_id: id, text });
    },
    // 카드 메모: 카드에 직접 적는 메모 (표·문서·비교·시각화·대시보드 카드)
    setCardMemo(id: string, text: string) {
      if (this.blocked()) return;
      const c = this.cards.find((x) => x.id === id); if (!c || (c.memo ?? "") === text) return;
      c.memo = text;
      this.emit({ type: "set_card_memo", card_id: id, text });
    },
    setLinkMemo(id: string, text: string) {
      if (this.blocked()) return;
      const l = this.links.find((x) => x.id === id); if (!l || (l.memo ?? "") === text) return;
      l.memo = text; l.memoBy = text ? this.author : undefined;
      this.emit({ type: "set_link_memo", link_id: id, text });
    },
    addDash(panel: string, pos = { x: 0, y: 0 }) {
      if (this.blocked()) return "";
      const id = this.newId();
      this.cards.push({ id, kind: "dash", panel, ...pos });
      this.emit({ type: "add_dash", card_id: id, panel });
      return id;
    },
    addLink(source: string, target: string) {
      if (this.blocked()) return;
      if (source === target || this.links.some((l) => (l.source === source && l.target === target) || (l.source === target && l.target === source))) return;
      const id = "l" + ++this.seq;
      this.links.push({ id, source, target });
      this.emit({ type: "add_link", link_id: id, source, target });
    },
    removeLink(id: string) {
      if (this.blocked()) return;
      this.links = this.links.filter((l) => l.id !== id);
      this.emit({ type: "remove_link", link_id: id });
    },
    // 시각화 카드: 비교 카드에서(그 비교의 표를 이어받음) 또는 고른 표 카드에서 바로
    addChart(compareId: string) {
      const c = this.cards.find((x) => x.id === compareId) as CompareCard | undefined; if (!c) return "";
      return this.addChartFrom(c.sources, { x: c.x, y: c.y + 520 }, compareId);
    },
    addChartFrom(sources: string[], pos?: { x: number; y: number }, compareId?: string) {
      if (this.blocked()) return "";
      const src = sources.filter((s) => this.cards.some((c) => c.id === s && c.kind === "table")); if (src.length < 2) return "";
      const ss = this.cards.filter((c) => src.includes(c.id)), id = this.newId();
      pos ??= { x: Math.max(...ss.map((c) => c.x)) + 620, y: Math.min(...ss.map((c) => c.y)) + 560 };
      this.cards.push({ id, kind: "chart", sources: src, compareId, version: 0, ...pos });
      this.emit({ type: "create_compare", card_id: id, sources: src, view: "chart" });
      return id;
    },
    remove(id: string) {
      if (this.blocked()) return;
      this.links = this.links.filter((l) => l.source !== id && l.target !== id);
      this.cards = this.cards.filter((c) => c.id !== id);
      this.selected = this.selected.filter((s) => s !== id);
      for (const c of this.cards) if (c.kind === "compare" || c.kind === "chart") c.sources = c.sources.filter((s) => s !== id);
      this.emit({ type: "remove_card", card_id: id });
    },
    // 비교표: 고른 표 카드(ids, Shift 다중 선택) 또는 체크한 표 카드(예시 불러오기)
    createCompare(ids?: string[]) {
      if (this.blocked()) return null;
      const src = ids ? this.cards.filter((c): c is TableCard => c.kind === "table" && ids.includes(c.id)) : this.checked; if (src.length < 2) return null;
      const id = this.newId();
      const x = Math.max(...src.map((c) => c.x)) + 620, y = Math.min(...src.map((c) => c.y));
      this.cards.push({ id, kind: "compare", sources: src.map((c) => c.id), x, y, version: 0 });
      src.forEach((c) => (c.checked = false));
      this.emit({ type: "create_compare", card_id: id, sources: src.map((c) => c.id) });
      return id;
    },
    // 대응 확정 · 거부. accepted null = 취소 (서버 compare()가 확정 표시 · 거부 행 이동에 반영)
    confirmMapping(conceptId: string, company: string, cells: string[], accepted: boolean | null) {
      if (this.blocked()) return;
      this.emit({ type: "confirm_mapping", concept_id: conceptId, company, cells, accepted });
    },
    // ---------- 분석 세션 (본인만) ----------
    akey() { return `${this.canvasId}|${this.user?.id ?? ""}`; },
    readAnalyses() { return readA()[this.akey()] ?? []; },
    pushAnalysis(s: Session) {
      this.analyses = [s, ...this.analyses];
      const all = readA(); all[this.akey()] = this.analyses; writeA(all);
    },
    // 업데이트: 같은 세션 자리에 다시 만든 결과를 넣음 (새 세션을 쌓지 않음)
    replaceAnalysis(s: Session) {
      this.analyses = this.analyses.map((x) => x.id === s.id ? s : x);
      const all = readA(); all[this.akey()] = this.analyses; writeA(all);
    },
    // 분석 세션 지우기 (본인 세션만 보이므로 본인 것만 지움)
    removeAnalysis(id: string) {
      this.analyses = this.analyses.filter((x) => x.id !== id);
      const all = readA(); all[this.akey()] = this.analyses; writeA(all);
    },
    // 평가는 다음 분기 자료가 들어온 뒤 그 세션에 붙인다 (세션의 검토 · 질문은 그대로)
    attachEval(id: string, ev: Session["eval"]) {
      const s = this.analyses.find((x) => x.id === id); if (!s) return;
      s.eval = ev;
      const all = readA(); all[this.akey()] = this.analyses; writeA(all);
    },
  },
});
