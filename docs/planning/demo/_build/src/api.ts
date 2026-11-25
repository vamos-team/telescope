// 백엔드(FastAPI)와 같은 타입. 호출은 브라우저 내 구현으로 대신한다.
export interface Row { r: number; label: string; path: string; grp: string; depth: number; notes: string[];
  values: (number | null)[]; acodes: (string | null)[] }
export interface SourceTable { table_id: string; rcept_no: string; company: string; title: string; note_no: string | null;
  basis: string; period: string; unit: string; caption: string[]; header_rows: number; label_cols: number;
  cols: string[]; rows: Row[]; raw: [string, string, number, number, number][][] }
export interface SearchResult { table_id: string; company: string; title: string; basis: string; period: string;
  unit: string; score: number; hits: { r: number; label: string; grp: string; why: string }[] }
export interface Part { card_id: string; cell_id: string; label: string; value_bil: number | null }
export interface CompareLine { label: string; why: string; values: (Part[] | null)[]; concept_id: string | null;
  note: string | null; totals?: (number | null)[] }
export interface CompareResult { groups: { company: string; basis: string }[]; lines: CompareLine[];
  unmatched: CompareLine[]; warnings: string[] }
export const UNIT_TO_BIL: Record<string, number> = { 원: 1e-9, 천원: 1e-6, 백만원: 1e-3, 십억원: 1, 억원: 0.1 };
export type CanvasEvent = { type: string; [k: string]: unknown };
// 캔버스에 올리는 비정형 자료 (IR·뉴스·사내 자료). 사내 자료는 파서 모델이 만든 요약본
export interface DocFigure { label: string; value: number; unit: string; period: string }
export interface Doc { id: string; kind: "ir" | "news" | "internal"; company: string; title: string; publisher: string; date: string;
  summary: string; figures: DocFigure[]; url: string; tags: string[]; uploaded?: boolean }
// IR 자료 제목은 회사명 + 분기만 (전체 제목은 title 속성으로). 뉴스·사내 자료는 원래 제목
export const docTitle = (d: Doc) => d.kind === "ir" ? `${d.company} ${d.title.match(/[1-4]Q\d{2}/)?.[0] ?? d.date.slice(0, 7)}` : d.title;
// 근거 한 줄: 표 셀(공시) 또는 문서 수치·요약. cite가 프롬프트·초안에서 쓰는 근거 ID
export interface Evidence { tag: string; company: string; label: string; value: number | null; unit: string; period: string;
  cite: string; card_id: string; source: "공시" | "IR" | "뉴스" | "사내"; text?: string; edited?: boolean }
export interface Deliverable { id: string; name: string; goal: string; sections: { title: string; tags: string[] }[]; assume: string[] }
export interface Insight { id: string; title: string; concepts: string[]; ai: string; news: string[] }
export interface Analysis { focus: string; tag_names: Record<string, string>; deliverables: Deliverable[]; insights: Insight[];
  questions: { topic: string; tags: string[]; level: string; q: string; why: string }[];
  next_quarter: { label: string; date: string; company: string; actuals: Record<string, number>;
    issues: { id: string; topic: string; title: string; publisher: string; url: string }[] } }
export interface Context { layers: Record<string, string>; hashes: Record<string, string>; messages: { role: string; content: string }[] }

// 정적 HTML 버전: 서버 호출 대신 src/local/server.ts의 브라우저 내 구현을 쓴다. 함수 모양은 원본과 같다.
import * as local from "./local/server";
const p = <T>(f: () => T) => new Promise<T>((ok, no) => { try { ok(f()); } catch (e) { no(e); } });
export const api = {
  search: (q: string) => p(() => local.search(q)),
  table: (id: string) => p(() => local.table(id)),
  tableInfo: local.table,  // 동기 조회 (목록 표시용)
  postEvents: (sid: string, events: CanvasEvent[]) => p(() => local.postEvents(sid, events)),
  compare: (sid: string, cardId: string) => p(() => local.compare(sid, cardId)),
  meta: local.meta,
  noteTable: local.noteTable,
  warmup: (_sid: string) => Promise.resolve(null),  // LLM 없음
  docs: local.docs,
  registerDoc: local.registerDoc,
  searchDocs: (q: string, kinds: Doc["kind"][]) => p(() => local.searchDocs(q, kinds)),
  searchQuestion: (q: string) => p(() => local.searchQuestion(q)),
  recommend: local.recommend,
  tagName: local.tagName,
  conceptsFor: local.conceptsFor,
  peerFacts: local.peerFacts,
  context: (sid: string, task: string) => p(() => local.context(sid, task)),
  evidence: (sid: string) => p(() => local.evidence(sid)),
  cardTags: (sid: string) => p(() => local.cardTags(sid)),
  analysis: local.analysis,
  ask: (_sid: string, _q: string) => Promise.reject(new Error("정적 HTML 버전에서는 LLM 질의를 지원하지 않습니다")),
};
