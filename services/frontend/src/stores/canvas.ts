// 캔버스 상태 + 이벤트 큐. 화면은 로컬 상태로 즉시 갱신하고, 이벤트는 묶어서 서버로 보낸다.
// 서버가 이벤트 로그의 원본(source of truth)이며, 컨텍스트 층 해시를 돌려준다.
import { defineStore } from "pinia";
import { api, docTitle, type CanvasEvent, type Doc } from "../api";
import { panelOf } from "../local/dashboard";
import type { Session } from "../local/ai";
import { accessToken, setAccessToken, SYNC_MODE } from "../sync/config";
import { Outbox, SyncError } from "../sync/outbox";
import { SeqTracker } from "../sync/seq";
import { applyRemote } from "../sync/apply";
import { fromServerEvent, type CanvasNotice, type CursorMessage, type EventsMessage, type PresenceMessage, type UserRef } from "../sync/protocol";
import { canvasErrorMessage, createCanvas, deleteCanvas, fetchMe, getActivities, getDocument, getShares, listCanvases, login as serverLogin, openCanvas,
  postEvents as postServerEvents, putShares, renameCanvas, searchUsers, signup as serverSignup, versionsOf,
  type AuthResult, type AuthUser, type CanvasShares, type CanvasSummary, type SignupInput } from "../sync/http";
import { connectCanvas, type RealtimeConnection } from "../sync/realtime";
import { fromSnapshot, replayEvents } from "../sync/snapshot";

// 서버 모드 연동 객체. 반응형 상태(Pinia)에 넣지 않는다(클래스 내부 상태를 프록시로 감싸지 않게).
let outbox: Outbox | null = null;
let tracker: SeqTracker | null = null;
let connection: RealtimeConnection | null = null;
const serverDocIds = new Set<string>();
let serverReload = 0;
let authAttempt = 0;  // 로그인·회원가입 요청 번호. 응답이 늦게 와도 가장 최근 요청만 반영
/** 서버 응답·메시지의 사람을 화면 이름으로. backend가 이름을 채운다(#149). 사용자가 없으면(삭제 등) ID로 보인다. */
const nameOfUser = (u: UserRef | null | undefined) => (u == null ? "다른 사용자" : u.name ?? `사용자 ${u.id}`);
/** 시각을 활동 로그 표시(시:분)로. */
const hhmm = (d: Date) => d.toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" });

// memo: 카드 메모 (카드에 직접 적은 메모). 메모는 한 종류이고, 지시 · 근거 · 의견은 분석(검토)에서 에이전트가 가린다
export interface TableCard { id: string; kind: "table"; tableId: string; rows: number[]; cols: number[];
  vcol: number; edits: Record<string, number | null>; sort: { key: string | number; desc: boolean } | null;
  checked: boolean; x: number; y: number; memo?: string }
export interface CompareCard { id: string; kind: "compare"; sources: string[]; x: number; y: number; version: number; memo?: string }
export interface DocCard { id: string; kind: "doc"; docId: string; x: number; y: number; memo?: string }
// 메모 카드 (F-07): 다른 카드에 선으로 이어 다는 메모. 카드 메모 칸은 없음
// authorId: 단 사람의 사용자 id(예전 local 저장본에는 없어 이름으로 비교)
export interface MemoCard { id: string; kind: "memo"; author: string; authorId?: number; text: string; x: number; y: number; memo?: string }
// 시각화 카드: 고른 표 카드(sources)의 계정 대응 결과를 막대그래프로. 서버에는 비교 카드와 같은 계산(create_compare, view chart)으로 등록
export interface ChartCard { id: string; kind: "chart"; sources: string[]; compareId?: string; version: number; x: number; y: number; memo?: string }
// 대시보드 카드: 자료 검색 대시보드 패널을 캔버스로 가져온 것 (panel = DASH_PANELS id)
export interface DashCard { id: string; kind: "dash"; panel: string; x: number; y: number; memo?: string }
// 카드 메모를 단 사람(이름 · id). 서버는 카드 메모 작성자를 주지 않아 서버에서 연 카드에는 없다
export type Card = (TableCard | CompareCard | DocCard | MemoCard | ChartCard | DashCard) & { memoBy?: string; memoById?: number };
// 선: 관계 태그 없음(에이전트가 판단). memo = 선 메모. rel은 예전 저장본 호환용
export interface Link { id: string; source: string; target: string; rel?: string; memo?: string; memoBy?: string; memoById?: number }  // memoBy · memoById: 선 메모를 단 사람
// 메모 3종 (카드 메모 · 메모 카드 · 선 메모). 왼쪽 메모 목록과 분석 입력에 씀
export interface Note { key: string; kind: "card" | "memo" | "link"; cardId?: string; linkId?: string; where: string; text: string; author?: string; mine?: boolean }
// 데이터 성격(강조색): dart 공시 / ir / news / internal / viz(비교·시각화·대시보드) / memo
// 팀 공유 목업: 같은 캔버스를 보는 팀원. 실제 서비스에서는 로그인 사용자와 이벤트 로그의 작성자
// 로그인: 사번 + 비밀번호(목업이라 아무 값이나 통과). 들어가면 캔버스 홈에서 볼 수 있는 캔버스를 고른다 (실제 서비스는 사내 SSO)
// 부서는 기획서 1장의 사업 조직
export const DEPTS = ["네트워크센터", "AIDC사업본부", "세일즈마케팅본부", "프로덕트브랜드본부"];
// 직무: 회원가입에서 고르는 목록(목업 구성원의 직무와 같음)
export const JOBS = ["재무기획", "네트워크 기술", "AIDC 사업", "세일즈", "상품", "부서장"];
// 사람은 backend와 같은 모양: id는 숫자(users PK), 사번(employeeNo)은 로그인 · 표시에만 쓴다
export const ALL_MEMBERS = [
  { id: 1, employeeNo: "1001", name: "이재무", dept: "네트워크센터", job: "재무기획" }, { id: 2, employeeNo: "1002", name: "박망", dept: "네트워크센터", job: "네트워크 기술" },
  { id: 3, employeeNo: "1003", name: "김센터", dept: "네트워크센터", job: "부서장" },
  { id: 4, employeeNo: "2001", name: "최예산", dept: "AIDC사업본부", job: "재무기획" }, { id: 5, employeeNo: "2002", name: "정데이터", dept: "AIDC사업본부", job: "AIDC 사업" },
  { id: 6, employeeNo: "2003", name: "한본부", dept: "AIDC사업본부", job: "부서장" },
  { id: 7, employeeNo: "3001", name: "오마케", dept: "세일즈마케팅본부", job: "재무기획" }, { id: 8, employeeNo: "3002", name: "윤영업", dept: "세일즈마케팅본부", job: "세일즈" },
  { id: 9, employeeNo: "4001", name: "서기획", dept: "프로덕트브랜드본부", job: "재무기획" }, { id: 10, employeeNo: "4002", name: "강상품", dept: "프로덕트브랜드본부", job: "상품" }];
/** 로그인한 사용자. local 목업과 서버 로그인이 같은 모양이다. job은 홈·왼쪽 패널 표시용 */
export interface CanvasUser { id: number; employeeNo: string; name: string; dept: string; job?: string }
/** 사람 찾기 결과·편집할 사람. local은 목업 구성원, server는 GET /users?q= 결과 */
export interface Person { id: number; employeeNo: string; name: string; dept: string; job: string }
export const memberOf = (name: string) => ALL_MEMBERS.find((m) => m.name === name);
export const memberById = (id: number) => ALL_MEMBERS.find((m) => m.id === id);
export const memberByEmployeeNo = (no: string) => ALL_MEMBERS.find((m) => m.employeeNo === no.trim());
export const who = (name: string) => { const m = memberOf(name); return m ? `${m.name} · ${m.dept} · ${m.job}` : name; };

// 캔버스(세션) 저장: 카드·연결 스냅숏 + 서버로 보낸 이벤트 로그. 다시 열면 이벤트를 새 세션에 재생한다
// ponytail: localStorage(같은 브라우저 안에서만 공유). 실제 서비스는 서버 이벤트 저장소
// 공유(F-08 권한 목업): 소유자와 직접 초대한 사람별 권한(편집 가능 / 보기 가능)만 둔다.
// 일반 액세스(회사 전체 보기)는 두지 않는다. 공유 설정은 소유자만 바꾼다 (docs/backend/api-spec.md 4장)
export type Role = "owner" | "edit" | "view";
// ownerId · share 키는 사용자 숫자 ID(share 키는 JSON이라 문자열 "3"). 예전 저장분은 사번 문자열("1001")이었다
// myRole: 서버 모드에서 서버가 알려 준 내 권한(목록 · 열기 응답). 있으면 공유 정보로 계산하지 않고 이 값을 쓴다
export interface CanvasMeta { id: string; name: string; dept: string; owner: string; updated: string; updatedBy: string; count: number;
  ownerId?: number | string; share?: Record<string, "edit" | "view">; quarter?: string; myRole?: Role }
/** 서버 목록 한 줄 → 화면 메타. 공유 명단은 목록에 없어 공유 창을 열 때 따로 읽는다(loadShares) */
export const metaOfServer = (s: CanvasSummary): CanvasMeta => ({
  id: String(s.id), name: s.name, dept: s.owner.dept ?? "", owner: nameOfUser(s.owner), ownerId: s.owner.id,
  updated: s.updatedAt, updatedBy: nameOfUser(s.updatedBy), count: s.cardCount, quarter: s.quarter, share: {}, myRole: s.myRole });
/**
 * 캔버스의 공유 정보(소유자 ID, 초대한 사람별 권한)를 돌려준다.
 * 예전 저장분은 사번으로 저장돼 있어(ownerId가 문자열) 숫자 ID로 바꿔 읽고, 공유 필드가 없으면 소유자만 있는 것으로 읽는다.
 */
export const shareOf = (m: CanvasMeta): { ownerId: number; share: Record<string, "edit" | "view"> } => {
  if (typeof m.ownerId === "number") return { ownerId: m.ownerId, share: m.share ?? {} };
  const ownerId = (m.ownerId ? memberByEmployeeNo(m.ownerId)?.id : undefined) ?? memberOf(m.owner)?.id ?? 0;
  const share = Object.fromEntries(Object.entries(m.share ?? {}).flatMap(([no, r]) => {
    const id = memberByEmployeeNo(no)?.id; return id ? [[String(id), r]] : [];
  }));
  return { ownerId, share };
};
export const ROLE: Record<Role, string> = { owner: "소유자", edit: "편집 가능", view: "보기 가능" };
export const when = (iso: string) => {
  const d = new Date(iso), m = Math.round((Date.now() - d.getTime()) / 60000);
  return m < 1 ? "방금" : m < 60 ? `${m}분 전` : m < 1440 ? `${Math.round(m / 60)}시간 전` : d.toLocaleDateString("ko-KR");
};
// 캔버스 분기: 만들 때 고름. 예전 저장분은 기본 분기로 읽음
export const QUARTERS = ["2025년 1분기", "2025년 2분기", "2025년 3분기", "2025년 4분기", "2026년 1분기", "2026년 2분기", "2026년 3분기", "2026년 4분기"];
export const DEFAULT_QUARTER = "2026년 1분기";
export const quarterOf = (m?: CanvasMeta) => m?.quarter ?? DEFAULT_QUARTER;
// 사람 찾기 (공유 창 · 새 캔버스 창): 입력이 비면 추천(같은 부서 먼저), 입력하면 사번 · 이름 · 부서로 찾음. 최대 6명
export function findPeople(q: string, exclude: Set<number>, dept: string) {
  const t = q.trim(), rest = ALL_MEMBERS.filter((m) => !exclude.has(m.id));
  if (!t) return [...rest.filter((m) => m.dept === dept), ...rest.filter((m) => m.dept !== dept)].slice(0, 6);
  return rest.filter((m) => m.employeeNo.includes(t) || m.name.includes(t) || m.dept.includes(t)).slice(0, 6);
}
/** 사용자 u가 캔버스 m에서 가진 권한을 돌려준다. 소유자 → 직접 초대 권한 순으로 보고, 둘 다 아니면 권한 없음(null). */
export function roleOf(m: CanvasMeta, u: { id: number } | null): Role | null {
  if (!u) return null;
  if (m.myRole) return m.myRole;  // 서버 모드: 서버가 정한 내 권한
  const s = shareOf(m);
  if (s.ownerId === u.id) return "owner";
  return s.share[String(u.id)] ?? null;
}
// uploads: 이 캔버스 카드가 쓰는 업로드 문서(파서 요약본). 새로고침 뒤 같은 ID로 다시 등록해 카드가 사라지지 않게 함
interface Saved { meta: CanvasMeta; cards: Card[]; links: Link[]; seq: number; activity: { author: string; text: string; time: string }[]; log: CanvasEvent[]; uploads?: Doc[] }
const KEY = "telescope-canvas:v1";
// 분석 세션: 페이지 · 사용자별로 따로 저장하고, 결과는 고치지 않고 새로 쌓는다. 공유 결과의 조회 범위는 캔버스 권한을 따른다(docs/backend/api-spec.md 4장)
const AKEY = "telescope-analyses:v1";
const readA = (): Record<string, Session[]> => { try { return JSON.parse(localStorage.getItem(AKEY) ?? "{}"); } catch { return {}; } };
const writeA = (all: Record<string, Session[]>) => { try { localStorage.setItem(AKEY, JSON.stringify(all)); } catch { /* 저장 불가: 화면에서만 유지 */ } };
// 지운 세션: 결과는 그대로 두고 내 목록에서 숨길 세션(처음 세션 ID)만 따로 적는다
const HKEY = "telescope-analyses-hidden:v1";
const readH = (): Record<string, string[]> => { try { return JSON.parse(localStorage.getItem(HKEY) ?? "{}"); } catch { return {}; } };
const writeH = (all: Record<string, string[]>) => { try { localStorage.setItem(HKEY, JSON.stringify(all)); } catch { /* 저장 불가 */ } };
const readAll = (): Record<string, Saved> => { try { return JSON.parse(localStorage.getItem(KEY) ?? "{}"); } catch { return {}; } };
const writeAll = (all: Record<string, Saved>) => { try { localStorage.setItem(KEY, JSON.stringify(all)); } catch { /* 저장 불가(사생활 보호 모드 등): 화면에서만 유지 */ } };
const now = () => new Date().toISOString();
const ACT: Record<string, string> = { add_card: "공시 표를 올림", add_doc: "자료를 올림", add_memo: "메모를 남김", edit_memo: "메모를 고침", set_card_memo: "카드 메모를 고침", set_link_memo: "선 메모를 고침", add_dash: "대시보드를 올림",
  create_compare: "비교 카드를 만듦", add_link: "카드를 연결함", remove_link: "연결을 지움",
  remove_card: "카드를 지움", clear_cards: "카드를 모두 지움", edit_cell: "값을 수정함", confirm_mapping: "계정 대응을 확정함" };

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
    user: null as CanvasUser | null,
    // 서버 모드 새로고침: 저장된 토큰으로 본인을 조회하는 동안 로그인 화면이 깜빡이지 않게 비워 둔다
    restoring: SYNC_MODE === "server" && !!accessToken(),
    canvasId: "", canvasName: "",
    creating: false,  // 새 캔버스 창
    log: [] as CanvasEvent[],  // 이 캔버스에서 서버로 보낸 이벤트 전체 (저장·재생용)
    saved: 0,  // 저장할 때마다 올려 목록을 다시 읽게 함
    rev: 0,  // 근거에 영향을 주는 조작마다 오름 (결과 패널의 "다시 생성" 안내 기준)
    error: "",  // 이벤트 반영·캔버스 복원 실패 안내
    // ---- 서버 모드(SYNC_MODE=server)에서만 쓰는 상태 ----
    serverRole: null as Role | null,  // 캔버스 열기 응답·권한 변경 알림의 내 권한
    serverQuarter: null as string | null,
    myNewMemo: null as string | null,  // 이 화면에서 내가 마지막으로 만든 메모 카드. 그 메모만 쓰는 중 · 자동 포커스로 연다
    serverCanvases: [] as CanvasMeta[],  // 캔버스 홈 목록(GET /canvases)
    serverShares: null as CanvasShares | null,  // 지금 캔버스의 공유 명단(GET·PUT /shares). 공유 창을 열 때 읽는다
    people: {} as Record<number, string>,  // 실시간 메시지로 알게 된 사람 이름(커서·접속자 표시), 키는 사용자 ID
    cursors: {} as Record<string, { x: number; y: number }>,  // 다른 사람 커서(캔버스 좌표), 키는 사용자 ID
    present: [] as number[],  // 지금 이 캔버스에 들어와 있는 사람(사용자 ID)
  }),
  getters: {
    // 볼 수 있는 캔버스 전부 (소유 · 초대 · 부서 · 회사 접근)
    // 서버 모드는 서버 목록(최근 수정 순으로 옴), 로컬 모드는 브라우저에 저장된 캔버스 중 권한이 있는 것
    canvases: (s): CanvasMeta[] => { void s.saved; if (SYNC_MODE === "server") return s.serverCanvases;
      return Object.values(readAll()).map((x) => x.meta).filter((m) => roleOf(m, s.user))
      .sort((a, b) => b.updated.localeCompare(a.updated)); },
    /** 사람 이름(커서 · 접속자). 실시간 메시지로 받은 이름이 없으면 ID로 보인다 */
    nameOf: (s) => (id: number) => s.people[id] ?? `사용자 ${id}`,
    meta(): CanvasMeta | undefined { return this.canvases.find((m) => m.id === this.canvasId); },
    pageQuarter(): string { return this.serverQuarter || quarterOf(this.meta); },
    // 서버 모드는 서버가 알려 준 권한, 로컬 모드는 저장된 공유 정보로 계산. 메타가 없으면(권한이 풀림 등) 권한 없음
    role(): Role | null { return SYNC_MODE === "server" ? this.serverRole : this.meta ? roleOf(this.meta, this.user) : null; },
    readOnly(): boolean { return this.role === "view"; },
    canEdit(): boolean { return this.role === "owner" || this.role === "edit"; },  // 소유자 · 편집 권한만 편집
    /** 내가 단 메모인지: id가 있으면 id로, 없으면(예전 local 저장본) 이름으로. 작성자를 모르면 남의 것으로 본다. 소유자도 예외 없음 */
    ownsMemo(): (id?: number, name?: string) => boolean {
      return (id, name) => (id != null ? id === this.user?.id : !!name && name === this.author);
    },
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
      const mine = this.ownsMemo;
      return [...this.cards.flatMap((c): Note[] => c.kind !== "memo" && c.memo ? [{ key: "cm" + c.id, kind: "card", cardId: c.id, where: this.cardTitle(c.id), text: c.memo, author: c.memoBy, mine: mine(c.memoById, c.memoBy) }] : []),
        ...this.cards.flatMap((c): Note[] => c.kind === "memo" && c.text.trim() ? [{ key: "mc" + c.id, kind: "memo", cardId: c.id, where: linkedTo(c.id).join(", ") || "연결 없음", text: c.text.trim(), author: c.author, mine: mine(c.authorId, c.author) }] : []),
        ...this.links.flatMap((l): Note[] => l.memo ? [{ key: "lm" + l.id, kind: "link", linkId: l.id, where: `${this.cardTitle(l.source)} → ${this.cardTitle(l.target)}`, text: l.memo, author: l.memoBy, mine: mine(l.memoById, l.memoBy) }] : [])];
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
      outbox?.push(e);  // 서버 모드: 서버로 보낼 대기열에도 쌓음(작성자는 서버가 토큰으로 정함)
      if (e.type !== "move_card") this.rev++;  // 이동은 생성 컨텍스트에 영향 없음
      if (ACT[e.type]) this.activity.unshift({ author: this.author, text: ACT[e.type] + (e.card_id ? ` (${e.card_id})` : ""),
        time: new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) });
      clearTimeout(this.timer);
      this.timer = window.setTimeout(() => { this.flush().catch((err) => console.error(err)); }, 150); // 드래그 등 연속 조작은 묶어서 전송
    },
    /** 대기 중인 조작을 보낸다. 남김없이 반영했으면 true (분석은 false면 시작하지 않는다). */
    async flush(): Promise<boolean> {
      const serverOk = await this.flushServer();
      if (!this.queue.length) return serverOk;
      const batch = this.queue.splice(0);
      let res;
      try {
        res = await api.postEvents(this.sessionId, batch);
      } catch (err) {  // 반영 못 한 조작은 저장 로그에 넣지 않고 큐 앞에 되돌려 다음 전송 때 다시 보냄
        console.error("이벤트 반영 실패", err);
        this.queue.unshift(...batch);
        this.error = "조작을 반영하지 못했습니다. 새로고침 후 다시 시도하세요.";
        return false;
      }
      this.log.push(...batch.filter((e) => e.type !== "move_card"));  // 서버가 받은 조작만 저장·재생 로그로
      this.tags = await api.cardTags(this.sessionId).catch(() => this.tags);
      this.lastChangedLayer = res.first_changed_layer;
      // 비교 카드는 서버 계산 결과를 다시 받아야 하므로 버전을 올린다
      for (const c of this.cards) if (c.kind === "compare" || c.kind === "chart") c.version++;
      // 카드 구성이 바뀌었으면 다음 질문에 대비해 prefix를 미리 채운다
      if (res.first_changed_layer && res.first_changed_layer <= "L2_workspace") api.warmup(this.sessionId).catch(() => {});
      this.save();
      return serverOk && !this.queue.length;
    },
    /**
     * 서버 모드: 대기열의 이벤트를 서버에 저장한다. 실패하면 같은 묶음을 다음에 다시 보내고,
     * 글 충돌(409 K003)이면 서버의 최신 글로 바꾸고 안내한다. 로컬 모드에서는 아무것도 하지 않는다.
     * 남김없이 저장했으면 true, 충돌 · 거부 · 재시도 대기 · 남은 묶음이 있으면 false.
     */
    async flushServer(): Promise<boolean> {
      if (!outbox || !this.canvasId) return true;
      const canvasId = this.canvasId;
      const result = await outbox.flush((batch) => {
        tracker?.markMine(batch.requestId);  // 응답보다 메시지가 먼저 올 수 있어 보내기 전에 표시
        return postServerEvents(canvasId, batch);
      });
      if (result.status === "conflict") {
        const { targetId, field, text } = result.conflict;
        const card = this.cards.find((c) => c.id === targetId), link = this.links.find((l) => l.id === targetId);
        if (field === "text" && card?.kind === "memo") card.text = text ?? "";
        else if (card) card.memo = text ?? undefined;
        else if (link) link.memo = text ?? undefined;
        this.error = "다른 사람이 먼저 고쳤습니다. 최신 글로 바꿨으니 확인한 뒤 다시 고쳐 주세요.";
      } else if (result.status === "rejected") {
        this.error = result.error.code === "K002" ? "보기 권한만 있습니다."
          : result.error.code === "K004" ? "다른 사람이 단 메모는 고치거나 지울 수 없습니다. 새로고침하면 서버 상태로 돌아갑니다."
          : "조작을 반영하지 못했습니다. 새로고침 후 다시 시도하세요.";
      } else if (result.status === "retry") {
        this.error = "서버에 연결하지 못했습니다. 연결되면 다시 보냅니다.";
      }
      return (result.status === "ok" || result.status === "idle") && !outbox?.pending;
    },
    /** 서버 모드: 캔버스를 서버에서 다시 읽어 화면을 맞춘다(처음 열 때, 재접속, 번호 누락). 보내지 못한 조작을 먼저 보낸다. */
    async reloadFromServer() {
      if (!this.canvasId) return;
      const canvasId = this.canvasId, reload = ++serverReload;
      await this.flushServer();
      if (reload !== serverReload) return;
      tracker?.reset();
      let snapshot;
      try {
        snapshot = await openCanvas(canvasId);
      } catch (err) {
        if (reload !== serverReload) return;
        for (const id of serverDocIds) api.docs.delete(id);
        serverDocIds.clear();
        this.analyses = [];
        this.serverRole = null;
        console.error("캔버스 열기 실패", err);
        this.error = "캔버스를 열지 못했습니다. 권한이 없거나 연결이 끊겼을 수 있습니다.";
        return;
      }
      if (reload !== serverReload) return;
      const { cards, links } = fromSnapshot(snapshot, nameOfUser);
      const docIds = cards.flatMap((c) => c.kind === "doc" ? [c.docId] : []);
      await this.loadServerDocs(docIds);
      if (reload !== serverReload) return;
      Object.assign(this, { cards, links, canvasName: snapshot.meta.name, serverRole: snapshot.meta.myRole, serverQuarter: snapshot.meta.quarter, analyses: this.readAnalyses() });
      this.upsertServerCanvas(snapshot.meta);  // 페이지 정보(cv.meta)가 홈을 거치지 않고 열어도 보이게
      this.loadActivities(canvasId, reload);
      outbox?.updateVersions(versionsOf(snapshot));
      if (snapshot.warnings.length) this.error = `'${snapshot.meta.name}' 캔버스 일부를 복원하지 못했습니다. 자료가 바뀌었을 수 있습니다.`;
      // 비교·태그는 아직 목업이 계산하므로 새 목업 세션에 지금 상태를 넣는다
      const sessionId = this.sessionId = crypto.randomUUID();
      await api.postEvents(sessionId, replayEvents(cards, links)).catch((err) => console.error(err));
      const tags = await api.cardTags(sessionId).catch(() => this.tags);
      if (reload !== serverReload) return;
      this.tags = tags;
      for (const c of this.cards) if (c.kind === "compare" || c.kind === "chart") c.version++;
      for (const d of tracker?.start(snapshot.lastSeq) ?? []) this.handleSeq(d);
    },
    cacheServerDoc(doc: Doc) {
      serverDocIds.add(doc.id);
      return api.registerDoc(doc);
    },
    async loadServerDocs(ids: string[], refresh = true) {
      const reload = serverReload;
      await Promise.all([...new Set(ids)].filter((id) => /^[1-9]\d*$/.test(id) && (refresh || !api.docs.has(id))).map(async (id) => {
        const doc = await getDocument(id).catch(() => null);
        if (reload !== serverReload) return;
        if (doc) this.cacheServerDoc(doc);
        else if (serverDocIds.delete(id)) api.docs.delete(id);
      }));
    },
    /** 서버 모드: 받은 이벤트 메시지를 번호 확인 후 반영한다. */
    onRemoteEvents(message: EventsMessage) {
      if (tracker) this.handleSeq(tracker.receive(message));
    },
    handleSeq(d: ReturnType<SeqTracker["receive"]>) {
      if (d.action === "gap") { this.reloadFromServer(); return; }
      if (d.action !== "apply" && d.action !== "own") return;
      outbox?.updateVersions(d.message.textVersions ?? {});
      if (d.action === "own") return;  // 내 조작은 이미 화면에 있다
      const author = nameOfUser(d.message.author), authorId = d.message.author?.id;
      const local = d.message.events.map(fromServerEvent);
      let changed = false;
      for (const e of local) {
        if (applyRemote(this, e, author, authorId)) changed = true;
        if (ACT[e.type]) this.activity.unshift({ author, text: ACT[e.type], time: hhmm(new Date()) });
      }
      if (!changed) return;
      if (local.some((e) => e.type !== "move_card")) this.rev++;
      // 비교·태그 계산용 목업에도 같은 변경을 넣는다
      const sessionId = this.sessionId;
      const docs = this.loadServerDocs(local.flatMap((e) => e.type === "add_doc" ? [String(e.doc_id)] : []), false);
      Promise.all([api.postEvents(sessionId, local), docs]).then(async () => {
        const tags = await api.cardTags(sessionId).catch(() => this.tags);
        if (sessionId !== this.sessionId) return;
        this.tags = tags;
        for (const c of this.cards) if (c.kind === "compare" || c.kind === "chart") c.version++;
      }).catch((err) => console.error(err));
    },
    onRemoteCursor(m: CursorMessage) {
      if (m.user.id === this.user?.id) return;  // 보낸 사람도 받으므로 내 커서는 무시
      this.rememberPerson(m.user);
      this.cursors = { ...this.cursors, [m.user.id]: { x: m.x, y: m.y } };
    },
    onPresence(m: PresenceMessage) {
      this.rememberPerson(m.user);
      if (m.type === "join") { if (!this.present.includes(m.user.id)) this.present = [...this.present, m.user.id]; return; }
      this.present = this.present.filter((id) => id !== m.user.id);
      const { [m.user.id]: _gone, ...rest } = this.cursors;
      this.cursors = rest;
    },
    /** 실시간 메시지의 사람 이름을 기억한다(커서 · 접속자 표시). 이름이 없으면(사용자 없음) 기억하지 않는다 */
    rememberPerson(u: UserRef) { if (u.name && this.people[u.id] !== u.name) this.people = { ...this.people, [u.id]: u.name }; },
    /** 권한이 사라지거나 캔버스가 삭제되면 홈으로, 권한이 바뀌면 편집 도구를 맞춘다. */
    async onNotice(n: CanvasNotice) {
      if (String(n.canvasId) !== this.canvasId) return;
      if (n.type === "role_changed") { this.serverRole = n.role ?? null; this.serverShares = null; return; }
      await this.goHome();
      this.error = n.type === "deleted" ? "캔버스가 삭제되었습니다." : "이 캔버스를 볼 권한이 없습니다.";
    },
    /** 서버 모드: 캔버스의 실시간 연결을 열고 서버에서 읽는다. */
    async openServer(id: string) {
      this.closeServer();
      this.canvasId = id;
      outbox = new Outbox(undefined, (cardId) => { const c = this.cards.find((x) => x.id === cardId); return c && { x: c.x, y: c.y }; });
      tracker = new SeqTracker();
      connection = connectCanvas(id, {
        onEvents: (m) => this.onRemoteEvents(m),
        onCursor: (m) => this.onRemoteCursor(m),
        onPresence: (m) => this.onPresence(m),
        onPresenceSnapshot: (users) => { users.forEach((u) => this.rememberPerson(u)); this.present = users.map((u) => u.id); },
        onNotice: (n) => { this.onNotice(n); },
        onConnected: () => { this.reloadFromServer(); },  // 재접속하면 놓친 변경이 있을 수 있어 다시 읽음
        onDisconnected: () => { this.cursors = {}; },
      });
      try { sessionStorage.setItem(KEY + ":canvas", id); } catch { /* 무시 */ }
      await this.reloadFromServer();
    },
    /** 서버 모드: 실시간 연결과 대기열을 닫는다(캔버스를 닫거나 바꿀 때). */
    closeServer() {
      serverReload++;
      for (const id of serverDocIds) api.docs.delete(id);
      serverDocIds.clear();
      connection?.close();
      connection = null; outbox = null; tracker = null;
      Object.assign(this, { serverRole: null, serverQuarter: null, serverShares: null, cursors: {}, present: [] });
    },
    /** 서버 모드: 내 커서 위치(캔버스 좌표)를 다른 사람에게 보낸다. */
    sendCursor(x: number, y: number) { connection?.sendCursor(x, y); },
    /**
     * 로그인 화면에서 들어감. server 모드는 backend 로그인(비밀번호 검사), local 모드는 목업 구성원(비밀번호 검사 없음).
     * 실패하면 오류를 던짐(문구는 authErrorMessage)
     */
    async signIn(employeeNo: string, password: string) {
      if (SYNC_MODE !== "server") { if (this.login(employeeNo) === false) throw new Error("사번을 확인하세요"); return; }
      return this.latestAuth(() => serverLogin(employeeNo.trim(), password));
    },
    /** 회원가입하고 바로 들어감. backend가 필요해 server 모드에서만 됨 */
    async signUp(input: SignupInput) {
      if (SYNC_MODE !== "server") throw new Error("회원가입은 서버 연결 모드(VITE_CANVAS_SYNC=server)에서만 할 수 있습니다.");
      return this.latestAuth(() => serverSignup(input));
    },
    /**
     * 로그인 요청이 끝나기 전에 회원가입으로 넘어가 가입하는 식으로 요청이 겹칠 수 있다.
     * 그 사이 더 새 요청(또는 로그아웃)이 있었으면 늦게 온 응답은 성공이든 실패든 버린다
     */
    async latestAuth(request: () => Promise<AuthResult>) {
      const n = ++authAttempt;
      let result: AuthResult;
      try { result = await request(); } catch (err) { if (n === authAttempt) throw err; return; }
      if (n !== authAttempt) return;
      return this.enter(result);
    },
    /** 서버 로그인·회원가입 응답으로 들어감. 토큰은 탭을 닫으면 사라지는 sessionStorage에 둠 */
    enter(result: AuthResult) {
      setAccessToken(result.accessToken);
      this.setServerUser(result.user);
      return this.restoreCanvas();
    },
    setServerUser(u: AuthUser) {
      this.user = { id: u.id, employeeNo: u.employeeNo, name: u.name, dept: u.dept ?? "", job: u.job ?? undefined }; this.author = u.name;
    },
    login(employeeNo: string) {  // local 모드: 목업 구성원 사번으로 들어감. 없는 사번이면 false
      const m = memberByEmployeeNo(employeeNo); if (!m) return false;
      this.user = { id: m.id, employeeNo: m.employeeNo, name: m.name, dept: m.dept, job: m.job }; this.author = m.name;
      try { sessionStorage.setItem(KEY + ":user", JSON.stringify(this.user)); } catch { /* 무시 */ }
      return this.restoreCanvas();  // 로그인하면 캔버스 홈. 새로고침이면 보던 캔버스로
    },
    restoreCanvas() {
      try {
        const id = sessionStorage.getItem(KEY + ":canvas");
        if (id && (SYNC_MODE === "server" ? /^[1-9]\d*$/.test(id) : this.canvases.some((m) => m.id === id))) return this.open(id);
      } catch { /* 무시 */ }
    },
    async goHome() {
      await this.flush(); this.save(); this.reset(); this.canvasId = ""; this.canvasName = "";
      try { sessionStorage.removeItem(KEY + ":canvas"); } catch { /* 무시 */ }
    },
    async logout() {  // 대기 중인 조작을 지금 사용자 이름으로 저장한 뒤 캔버스 상태를 비움 (다음 사용자가 이전 캔버스를 덮어쓰지 않게)
      authAttempt++;  // 아직 진행 중인 로그인·회원가입 응답이 로그아웃 뒤에 들어오지 않게
      await this.flush(); this.save();
      this.reset(); this.canvasId = ""; this.canvasName = ""; this.user = null;
      if (SYNC_MODE === "server") setAccessToken(null);
      try { sessionStorage.removeItem(KEY + ":user"); sessionStorage.removeItem(KEY + ":canvas"); } catch { /* 무시 */ }
    },
    async restoreUser() {  // 새로고침: server 모드는 저장된 토큰으로 본인 조회, local 모드는 저장된 사번
      if (SYNC_MODE === "server") {
        const token = accessToken();
        if (!token) { this.restoring = false; return; }
        this.restoring = true;
        // 응답을 기다리는 동안 로그인 화면에서 새로 로그인할 수 있다. 그때는 토큰이 바뀌므로 늦게 온 응답을 버린다
        let me: AuthUser;
        try {
          me = await fetchMe();
        } catch (err) {
          // 만료·위조: 다시 로그인. 네트워크 오류면 토큰을 남김. 그 사이 새로 받은 토큰은 지우지 않는다
          if (err instanceof SyncError && err.status === 401 && accessToken() === token) setAccessToken(null);
          this.restoring = false;
          return;
        }
        this.restoring = false;
        if (accessToken() !== token) return;
        this.setServerUser(me);
        return this.restoreCanvas();
      }
      // 예전 저장분은 { id: 사번 } 모양
      try { const u = JSON.parse(sessionStorage.getItem(KEY + ":user") ?? "null"); const no = u?.employeeNo ?? u?.id; if (typeof no === "string") return this.login(no); } catch { /* 무시 */ }
    },
    save() {
      // 서버 모드는 카드·목록·공유가 모두 서버에 있어 브라우저에 따로 저장하지 않는다(분석 세션은 pushAnalysis가 따로 저장)
      if (SYNC_MODE === "server" || !this.canvasId || !this.user) return;
      const all = readAll(), prev = all[this.canvasId]?.meta;
      if (prev && !["owner", "edit"].includes(roleOf(prev, this.user) ?? "")) return;  // 저장된 캔버스는 편집 권한이 있을 때만. 처음 저장(새 캔버스)은 통과
      all[this.canvasId] = { meta: { id: this.canvasId, name: this.canvasName, dept: prev?.dept ?? this.user.dept, owner: prev?.owner ?? this.author,
        updated: now(), updatedBy: this.author, count: this.cards.length, quarter: prev?.quarter, ...(prev ? shareOf(prev) : { ownerId: this.user.id, share: {} }) },
        cards: JSON.parse(JSON.stringify(this.cards)), links: this.links, seq: this.seq, activity: this.activity.slice(0, 200), log: this.log,
        uploads: SYNC_MODE === "local" ? this.cards.flatMap((c) => { const d = c.kind === "doc" ? api.docs.get(c.docId) : undefined; return d?.uploaded ? [d] : []; }) : undefined };
      writeAll(all); this.saved++;
    },
    reset() {
      clearTimeout(this.timer); this.queue = []; this.error = "";
      this.closeServer();
      Object.assign(this, { sessionId: crypto.randomUUID(), cards: [], links: [], tags: {}, activity: [], log: [], seq: 0, lastChangedLayer: null, focus: null, active: null, selected: [], analyses: [], myNewMemo: null });
      this.overlay = { tableId: null, match: [], cardId: null };
    },
    defaultName() { return `${this.user?.dept ?? ""} 페이지 ${this.canvases.filter((m) => roleOf(m, this.user) === "owner").length + 1}`; },
    // 새 캔버스: 이름, 분기, 초대할 사람과 권한(편집 · 보기)을 받아 만들고 엶
    async newCanvas(opt: { name?: string; quarter?: string; members?: { id: number; role: "edit" | "view" }[] } = {}) {
      const members = opt.members ?? [];
      if (SYNC_MODE === "server") {
        let made: CanvasSummary;
        try {
          made = await createCanvas({ name: opt.name?.trim() || this.defaultName(), quarter: opt.quarter ?? DEFAULT_QUARTER,
            editorIds: members.filter((m) => m.role === "edit").map((m) => m.id) });
        } catch (err) { this.error = `페이지를 만들지 못했습니다. ${canvasErrorMessage(err)}`; return; }
        this.upsertServerCanvas(made);
        // 만들기 요청은 편집할 사람만 받으므로, 보기 권한이 있으면 공유 명단을 한 번 더 저장한다
        let shareError = "";
        if (members.some((m) => m.role === "view")) {
          try { await putShares(String(made.id), members.map((m) => ({ userId: m.id, role: m.role }))); }
          catch (err) { shareError = `공유 설정을 바꾸지 못했습니다. 페이지 정보에서 다시 설정해 주세요. ${canvasErrorMessage(err)}`; }
        }
        await this.open(String(made.id));
        if (shareError) this.error = shareError;  // open()이 오류를 비우므로 연 뒤에 알린다
        return;
      }
      await this.flush(); this.save(); this.reset();
      this.canvasId = "cv" + Date.now().toString(36); this.canvasName = opt.name?.trim() || this.defaultName();
      this.save();
      const all = readAll(), x = all[this.canvasId];
      if (x) { x.meta = { ...x.meta, quarter: opt.quarter ?? DEFAULT_QUARTER, share: Object.fromEntries(members.map((m) => [String(m.id), m.role])) }; writeAll(all); this.saved++; }
      try { sessionStorage.setItem(KEY + ":canvas", this.canvasId); } catch { /* 무시 */ }
    },
    async open(id: string) {
      if (id === this.canvasId) return;
      await this.flush(); this.save();
      if (SYNC_MODE === "server") { this.reset(); await this.openServer(id); return; }
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
    async rename(name: string) {
      if (this.blocked()) return;
      if (SYNC_MODE === "server") {
        const id = this.canvasId;
        try {
          const s = await renameCanvas(id, name.trim());
          this.upsertServerCanvas(s);
          if (id === this.canvasId) this.canvasName = s.name;  // 비면 서버가 "제목 없는 캔버스"로 정한다
        } catch (err) { this.error = `이름을 바꾸지 못했습니다. ${canvasErrorMessage(err)}`; }
        return;
      }
      this.canvasName = name.trim() || "제목 없는 페이지"; this.save();
    },
    async removeCanvas(id: string) {  // 소유자만
      if (SYNC_MODE === "server") {
        try { await deleteCanvas(id); } catch (err) { this.error = `페이지를 지우지 못했습니다. ${canvasErrorMessage(err)}`; return; }
        this.serverCanvases = this.serverCanvases.filter((m) => m.id !== id);
        if (id === this.canvasId) await this.goHome();
        return;
      }
      const all = readAll(); if (!all[id] || roleOf(all[id].meta, this.user) !== "owner") return;
      delete all[id]; writeAll(all); this.saved++;
      if (id === this.canvasId) { this.reset(); this.canvasId = ""; this.canvasName = ""; }
    },
    thumb(id: string) { return (readAll()[id]?.cards ?? []).map((c) => ({ kind: c.kind, x: c.x, y: c.y })); },  // 홈 미리보기
    /** 지금 캔버스의 초대 명단(사람별 권한)을 바꾸고 바로 저장한다. 소유자만 바꿀 수 있고, 다른 권한이면 아무것도 하지 않는다. */
    async setShare(patch: { share?: Record<string, "edit" | "view"> }) {
      if (this.role !== "owner") return;
      if (SYNC_MODE === "server") {
        const members = Object.entries(patch.share ?? {}).map(([id, role]) => ({ userId: Number(id), role }));
        try { this.serverShares = await putShares(this.canvasId, members); }
        catch (err) { this.error = `공유 설정을 바꾸지 못했습니다. ${canvasErrorMessage(err)}`; return; }
        this.activity.unshift({ author: this.author, text: "공유 권한을 바꿈", time: hhmm(new Date()) });
        return;
      }
      const all = readAll(), x = all[this.canvasId]; if (!x) return;
      x.meta = { ...x.meta, ...shareOf(x.meta), ...patch }; writeAll(all); this.saved++;
      this.activity.unshift({ author: this.author, text: "공유 권한을 바꿈", time: new Date().toLocaleTimeString("ko-KR", { hour: "2-digit", minute: "2-digit" }) });
    },
    /** 서버 모드: 캔버스 홈 목록을 서버에서 읽는다(홈에 들어올 때). */
    async loadCanvases() {
      if (SYNC_MODE !== "server" || !this.user) return;
      try { this.serverCanvases = (await listCanvases()).map(metaOfServer); }
      catch (err) { this.error = `페이지 목록을 읽지 못했습니다. ${canvasErrorMessage(err)}`; }
    },
    /** 서버 응답의 캔버스 한 줄을 목록에 넣거나 바꾼다(만들기 · 이름 바꾸기 · 열기). 맨 앞으로 옮긴다(최근 수정). */
    upsertServerCanvas(s: CanvasSummary) {
      const m = metaOfServer(s);
      this.serverCanvases = [m, ...this.serverCanvases.filter((x) => x.id !== m.id)];
    },
    /** 서버 모드: 지금 캔버스의 공유 명단을 읽는다(공유 창을 열 때). 권한자 모두 볼 수 있다. */
    async loadShares() {
      if (SYNC_MODE !== "server" || !this.canvasId) return;
      const id = this.canvasId;
      try { const s = await getShares(id); if (id === this.canvasId) this.serverShares = s; }
      catch (err) { this.error = `공유 명단을 읽지 못했습니다. ${canvasErrorMessage(err)}`; }
    },
    /** 서버 모드: 활동 로그를 서버에서 읽는다(열 때 · 다시 읽을 때). 그 사이 다른 캔버스로 바뀌었으면 버린다. */
    async loadActivities(canvasId: string, reload: number) {
      const list = await getActivities(canvasId).catch((err) => { console.error("활동 로그 읽기 실패", err); return null; });
      if (!Array.isArray(list) || reload !== serverReload || canvasId !== this.canvasId) return;
      this.activity = list.map((a) => ({ author: nameOfUser(a.author), text: a.text, time: hhmm(new Date(a.createdAt)) }));
    },
    /**
     * 사람 찾기(공유 창 · 새 캔버스 창). local은 목업 구성원(빈 검색어면 같은 부서 추천), server는 GET /users?q=(빈 검색어면 빈 목록).
     * exclude: 이미 권한이 있거나 고른 사람
     */
    async searchPeople(q: string, exclude: Set<number>, signal?: AbortSignal): Promise<Person[]> {
      if (SYNC_MODE !== "server") return findPeople(q, exclude, this.user?.dept ?? "");
      if (!q.trim()) return [];
      const found = await searchUsers(q.trim(), signal);
      return found.filter((p) => !exclude.has(p.id)).map((p) => ({ id: p.id, employeeNo: p.employeeNo, name: p.name, dept: p.dept ?? "", job: p.job ?? "" }));
    },
    // 화면 상태를 바꾸기 전에 권한 확인. 편집 권한이 없으면 안내만 하고 막음 (키보드로 버튼을 눌러도 상태가 바뀌지 않게)
    blocked() { if (this.canEdit) return false; this.error = "보기 권한만 있습니다."; return true; },
    /** 남이 단 메모면 안내하고 true. 메모는 단 사람만 고치고 지운다 */
    notMyMemo(id?: number, name?: string) { if (this.ownsMemo(id, name)) return false; this.error = "다른 사람이 단 메모는 고치거나 지울 수 없습니다."; return true; },
    /** 카드·연결 ID. 여러 사람이 동시에 만들어도 겹치지 않게 UUID로 만든다(docs/backend/canvas.md D1). */
    newId() { this.seq++; return crypto.randomUUID(); },
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
    // 전체 지우기: 카드와 연결을 모두 지운다. 남의 메모도 지우는 유일한 길이라 서버에도 한 이벤트(clear_cards)로 보낸다
    clear() {
      if (this.blocked() || !this.cards.length) return;
      this.cards = []; this.links = []; this.selected = [];
      this.emit({ type: "clear_cards" });
    },
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
      this.cards.push({ id, kind: "memo", author, authorId: author === this.author ? this.user?.id : undefined, text, ...pos });
      this.myNewMemo = id;
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
      const c = this.cards.find((x) => x.id === id) as MemoCard | undefined;
      if (!c || this.blocked() || this.notMyMemo(c.authorId, c.author)) return;
      c.text = text;
      this.emit({ type: "edit_memo", card_id: id, text });
    },
    // 카드 메모: 카드에 직접 적는 메모 (표·문서·비교·시각화·대시보드 카드)
    setCardMemo(id: string, text: string) {
      if (this.blocked()) return;
      const c = this.cards.find((x) => x.id === id); if (!c || (c.memo ?? "") === text) return;
      if (c.memo && this.notMyMemo(c.memoById, c.memoBy)) return;
      c.memo = text; c.memoBy = text ? this.author : undefined; c.memoById = text ? this.user?.id : undefined;
      this.emit({ type: "set_card_memo", card_id: id, text });
    },
    setLinkMemo(id: string, text: string) {
      if (this.blocked()) return;
      const l = this.links.find((x) => x.id === id); if (!l || (l.memo ?? "") === text) return;
      if (l.memo && this.notMyMemo(l.memoById, l.memoBy)) return;
      l.memo = text; l.memoBy = text ? this.author : undefined; l.memoById = text ? this.user?.id : undefined;
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
      const id = this.newId();
      this.links.push({ id, source, target });
      this.emit({ type: "add_link", link_id: id, source, target });
    },
    removeLink(id: string) {
      if (this.blocked()) return;
      const l = this.links.find((x) => x.id === id);
      if (l?.memo && this.notMyMemo(l.memoById, l.memoBy)) return;  // 연결을 끊으면 남의 선 메모도 사라지므로
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
      const c = this.cards.find((x) => x.id === id);
      if (c?.kind === "memo" && this.notMyMemo(c.authorId, c.author)) return;
      // 카드를 지우면 그 카드의 카드 메모와 닿는 선 메모도 사라지므로, 남의 메모가 있으면 지우지 않는다
      if (c?.memo && this.notMyMemo(c.memoById, c.memoBy)) return;
      const other = this.links.find((l) => (l.source === id || l.target === id) && l.memo && !this.ownsMemo(l.memoById, l.memoBy));
      if (other && this.notMyMemo(other.memoById, other.memoBy)) return;
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
    akey() { return `${this.canvasId}|${this.user?.employeeNo ?? ""}`; },  // 예전 저장분과 같은 키(사번)를 쓴다
    readAnalyses() { const hid = new Set(readH()[this.akey()] ?? []); return (readA()[this.akey()] ?? []).filter((s) => !hid.has(s.base ?? s.id)); },
    // 새로 분석하기 · 업데이트 모두 새 결과를 앞에 쌓는다 (업데이트는 base로 같은 세션의 새 버전)
    pushAnalysis(s: Session) {
      this.analyses = [s, ...this.analyses];
      const all = readA(); all[this.akey()] = [s, ...(all[this.akey()] ?? [])]; writeA(all);
    },
    // 세션 지우기 = 내 목록에서 숨김. 결과(모든 버전)는 저장소에 그대로 둔다
    hideAnalysis(base: string) {
      this.analyses = this.analyses.filter((x) => (x.base ?? x.id) !== base);
      const h = readH(); h[this.akey()] = [...new Set([...(h[this.akey()] ?? []), base])]; writeH(h);
    },
    // 평가는 다음 분기 자료가 들어온 뒤 그 세션에 붙인다 (세션의 검토 · 질문은 그대로)
    attachEval(id: string, ev: Session["eval"]) {
      const s = this.analyses.find((x) => x.id === id); if (!s) return;
      s.eval = ev;
      const all = readA(); all[this.akey()] = (all[this.akey()] ?? []).map((x) => x.id === id ? { ...x, eval: ev } : x); writeA(all);
    },
  },
});
