// 백엔드(FastAPI) 없이 브라우저에서 돌리는 버전. _template/backend의 로직을 옮긴 것:
//   search/hybrid.py → search(), mapping/compare.py → compare(), canvas/events.py → apply()
//   context/compiler.py → context() (문서·메모·연결 층 추가)
// 임베딩 검색, LLM 질의(ask/warmup), 이벤트 영속화는 없다 (새로고침하면 초기화).
import data from "./data.json";
import NEWS from "./news.json";
import { shallowReactive } from "vue";
import { panelOf, tableOf, dashColumnIndices, dashText, dashEvidence } from "./dashboard";
import { quarterIndex, withinQuarter } from "./period";
import { UNIT_TO_BIL, type SourceTable, type SearchResult, type CompareResult, type CompareLine, type Part, type CanvasEvent, type Doc, type Evidence, type Context, type Analysis } from "../api";

interface Member { label: string; grp?: string; title?: string; alt?: Member[] }
interface Concept { id: string; name: string; alias?: string[]; members: Record<string, Member[]>; note?: string }
// view: 비교 계산을 쓰는 카드가 비교표(compare)인지 시각화(chart)인지
interface WCard { card_id: string; kind: "table" | "compare" | "doc" | "memo" | "dash"; seq: number; doc_id?: string; text?: string; author?: string; panel?: string; table_id?: string; rows: number[]; cols: number[];
  vcol: number | null; edits: Record<string, number | null>; sort: [string | number, boolean] | null; sources: string[]; view?: string }
interface Link { source: string; target: string; memo?: string }
// memos: 카드 메모 (카드와 따로 둠). mappings: "개념|회사" → 확정(true) · 거부(false)
interface Mapping { concept_id: string; company: string; cells: string[]; accepted: boolean }
interface Workspace { cards: Map<string, WCard>; links: Map<string, Link>; memos: Map<string, string>; mappings: Map<string, Mapping>; seq: number; layers: string[] }

const TABLES = new Map((data.tables as unknown as SourceTable[]).map((t) => [t.table_id, t]));
const CONCEPTS = data.concepts as Concept[];

const norm = (s: string) => (s ?? "").replace(/\s+/g, "").toLowerCase();
const cellId = (t: SourceTable, r: number, c: number) => `${t.table_id}#r${r}c${c}`;
const alts = (m: Member) => [m, ...(m.alt ?? [])];
const memberMatches = (m: Member, t: SourceTable, r: number) => {
  const row = t.rows[r];
  return norm(row.label) === norm(m.label) && (m.grp == null || row.grp === m.grp) && (m.title == null || t.title.includes(m.title));
};
export interface Example { cards: { table_id: string; rows: number[] | null; cols?: number[] | null; vcol?: number }[]; compares: number[][];
  docs: string[]; charts?: number[]; memos: { author: string; text: string }[]; links: [string, string, string?][] }
export const meta = { subtitle: (data as { subtitle?: string }).subtitle ?? "",
  example: (data as unknown as { example?: Example }).example ?? null };
export const analysis = (data as unknown as { analysis: Analysis }).analysis;
const SRC = (data as unknown as { sources: { docs: Doc[]; internal: Doc[] } }).sources;
// v01 데모 실제 뉴스 헤드라인(썸네일 포함)도 함께 등록
export const docs = shallowReactive(new Map<string, Doc>([...SRC.docs, ...SRC.internal, ...(NEWS.items as Doc[])].map((d) => [d.id, d])));
// 업로드한 사내 자료(파서 요약본)를 등록한다. 실제 서비스에서는 업로드 API → 파서 모델 → 임베딩 적재
export const registerDoc = (d: Doc) => { docs.set(d.id, d); return d; };
export const noteTable = (rcept: string, note: string) =>
  [...TABLES.values()].find((t) => t.rcept_no === rcept && t.note_no === note)?.table_id ?? null;
export const table = (id: string) => {
  const t = TABLES.get(id); if (!t) throw new Error(`table ${id} not found`); return t;
};

// ---------- 검색: 문자 bigram BM25 + 개념 사전 확장, RRF ----------
const IDX = [...TABLES.values()].flatMap((t) => t.rows.map((row) => ({ t, r: row.r, label: row.label })));
const bigrams = (s: string) => { s = norm(s); return s.length < 2 ? [s] : [...s.slice(1)].map((_, i) => s.slice(i, i + 2)); };
const DOCS = IDX.map((x) => bigrams(x.label));
const AVG = DOCS.reduce((a, d) => a + d.length, 0) / DOCS.length;
const IDF = (() => {
  const df = new Map<string, number>();
  for (const d of DOCS) for (const g of new Set(d)) df.set(g, (df.get(g) ?? 0) + 1);
  return new Map([...df].map(([g, f]) => [g, Math.log(1 + (DOCS.length - f + 0.5) / (f + 0.5))]));
})();
function bm25(q: string[], k1 = 1.2, b = 0.75) {
  return DOCS.map((d) => {
    let s = 0;
    for (const g of q) {
      const f = d.filter((x) => x === g).length;
      if (f) s += IDF.get(g)! * f * (k1 + 1) / (f + k1 * (1 - b + b * d.length / AVG));
    }
    return s;
  });
}

export function search(q: string, k = 20): SearchResult[] {
  // 엄격 일치: 질의의 bigram을 모두 가진 계정만 직접 일치로 본다 (data/demo/examples/search_*.json 기준).
  // 일부만 겹치는 계정(예: 감가상각 → 무형자산상각비)은 개념 사전으로만 잡힌다. 백엔드 hybrid.py는 아직 느슨한 일치.
  const qg = bigrams(q), sc = bm25(qg);
  const rankings: [number, string][][] = [
    sc.map((s, i) => [i, s] as const).filter(([i, s]) => s > 0 && qg.every((g) => DOCS[i].includes(g))).sort((a, b) => b[1] - a[1]).slice(0, 200).map(([i]) => [i, "직접 일치"]),
  ];
  const nq = norm(q);
  const con: [number, string][] = [];
  for (const c of CONCEPTS) {
    const hit = [c.name, ...(c.alias ?? [])].some((a) => { const na = norm(String(a)); return na && (nq.includes(na) || na.includes(nq)) && Math.min(na.length, nq.length) >= 2; });
    if (hit) IDX.forEach((x, i) => { if ((c.members[x.t.company] ?? []).some((m) => alts(m).some((a) => memberMatches(a, x.t, x.r)))) con.push([i, `대응: ${c.name}`]); });
  }
  rankings.push(con);

  const fused = new Map<number, number>(), why = new Map<number, string>();
  for (const ranking of rankings) ranking.forEach(([i, w], rank) => { fused.set(i, (fused.get(i) ?? 0) + 1 / (60 + rank)); if (!why.has(i)) why.set(i, w); });
  const top = [...fused.keys()].sort((a, b) => fused.get(b)! - fused.get(a)!).slice(0, k * 3);
  const byTable = new Map<string, SearchResult>();
  for (const i of top) {
    const { t, r, label } = IDX[i];
    if (!byTable.has(t.table_id)) byTable.set(t.table_id, { table_id: t.table_id, company: t.company, title: t.title, basis: t.basis,
      period: t.period, unit: t.unit, score: 0, hits: [] });
    const e = byTable.get(t.table_id)!;
    e.score += fused.get(i)!;
    e.hits.push({ r, label, grp: t.rows[r].grp, why: why.get(i)! });
  }
  return [...byTable.values()].sort((a, b) => b.score - a.score).slice(0, k);
}

// ---------- 이벤트 리듀서 ----------
const sessions = new Map<string, Workspace>();
const ws = (sid: string) => {
  if (!sessions.has(sid)) sessions.set(sid, { cards: new Map(), links: new Map(), memos: new Map(), mappings: new Map(), seq: 0, layers: ["", "", ""] });
  return sessions.get(sid)!;
};

function apply(w: Workspace, e: CanvasEvent) {
  const c = w.cards.get(e.card_id as string);
  const n = (x: unknown) => x as number, ns = (x: unknown) => x as number[];
  switch (e.type) {
    case "add_card": {
      if (!TABLES.has(e.table_id as string)) break;  // 데이터를 다시 만든 뒤 남은 옛 표 ID 등은 무시
      const cols = [...ns(e.cols)].sort((a, b) => a - b);
      w.cards.set(e.card_id as string, { card_id: e.card_id as string, kind: "table", seq: w.seq, table_id: e.table_id as string,
        rows: [...new Set(ns(e.rows))].sort((a, b) => a - b), cols, vcol: cols[0] ?? null, edits: {}, sort: null, sources: [] });
      break;
    }
    case "add_rows": if (c) c.rows = [...new Set([...c.rows, ...ns(e.rows)])].sort((a, b) => a - b); break;
    case "remove_row": if (c) c.rows = c.rows.filter((r) => r !== n(e.r)); break;
    case "remove_card":
      w.cards.delete(e.card_id as string); w.memos.delete(e.card_id as string);
      for (const o of w.cards.values()) o.sources = o.sources.filter((s) => s !== e.card_id);
      for (const [id, l] of w.links) if (l.source === e.card_id || l.target === e.card_id) w.links.delete(id);
      break;
    case "add_doc": case "add_memo": case "add_dash":
      w.cards.set(e.card_id as string, { card_id: e.card_id as string, kind: e.type === "add_doc" ? "doc" : e.type === "add_dash" ? "dash" : "memo", seq: w.seq,
        doc_id: e.doc_id as string | undefined, text: e.text as string | undefined, author: e.author as string | undefined, panel: e.panel as string | undefined,
        rows: [], cols: [], vcol: null, edits: {}, sort: null, sources: [] });
      break;
    case "edit_memo": if (c) c.text = e.text as string; break;
    case "set_card_memo": { const t = (e.text as string).trim(); if (t) w.memos.set(e.card_id as string, t); else w.memos.delete(e.card_id as string); break; }
    case "set_link_memo": { const l = w.links.get(e.link_id as string); if (l) l.memo = (e.text as string).trim(); break; }
    case "add_link": w.links.set(e.link_id as string, { source: e.source as string, target: e.target as string }); break;
    case "remove_link": w.links.delete(e.link_id as string); break;
    case "edit_cell": if (c) c.edits[`${e.r},${e.c}`] = e.value as number | null; break;
    case "revert_card": if (c) c.edits = {}; break;
    case "select_period": if (c) c.vcol = n(e.c); break;
    case "sort_card": if (c) c.sort = e.key == null ? null : [e.key as string | number, !!e.desc]; break;
    case "create_compare":
      w.cards.set(e.card_id as string, { card_id: e.card_id as string, kind: "compare", seq: w.seq, rows: [], cols: [], vcol: null,
        edits: {}, sort: null, sources: e.sources as string[], view: (e.view as string | undefined) ?? "compare" });
      break;
    case "confirm_mapping": {
      const k = `${e.concept_id}|${e.company}`;
      if (e.accepted == null) w.mappings.delete(k);
      else w.mappings.set(k, { concept_id: e.concept_id as string, company: e.company as string, cells: e.cells as string[], accepted: !!e.accepted });
      break;
    }
    // move_card: 좌표는 화면(store)에만. 컨텍스트에 영향 없음
  }
}

// 앞 층부터 비교해 처음 바뀐 층을 돌려준다 (L1~L3은 compile()과 같은 내용)
const layers = (w: Workspace) => { const L = compile(w, "").layers; return [L.L1_sources, L.L2_workspace, L.L3_edits]; };
const LAYER_NAMES = ["L1_sources", "L2_workspace", "L3_edits"];

export function postEvents(sid: string, events: CanvasEvent[]) {
  const w = ws(sid);
  for (const e of events) { w.seq++; apply(w, e); }
  const cur = layers(w), i = cur.findIndex((l, k) => l !== w.layers[k]);
  w.layers = cur;
  return { last_seq: w.seq, first_changed_layer: i < 0 ? null : LAYER_NAMES[i], context_hashes: {} };
}

// ---------- 비교 ----------
interface Group { company: string; basis: string; cards: [WCard, SourceTable][] }
const value = (card: WCard, t: SourceTable, r: number, c: number) => {
  const k = `${r},${c}`; return k in card.edits ? card.edits[k] : t.rows[r].values[c];
};
const part = (card: WCard, t: SourceTable, r: number, label: string): Part => {
  const c = card.vcol ?? 0, v = value(card, t, r, c);
  return { card_id: card.card_id, cell_id: cellId(t, r, c), label, value_bil: v == null ? null : v * UNIT_TO_BIL[t.unit] };
};
function find(g: Group, m: Member) {
  for (const cand of alts(m)) for (const [card, t] of g.cards) for (const r of card.rows)
    if (memberMatches(cand, t, r)) return { key: `${card.card_id}|${r}`, value: part(card, t, r, t.rows[r].label + (cand.grp ? `(${cand.grp})` : "")) };
  return null;
}
const total = (ps: Part[] | null) => !ps || ps.every((p) => p.value_bil == null) ? null : ps.reduce((a, p) => a + (p.value_bil ?? 0), 0);

export function compare(sid: string, cardId: string): CompareResult {
  const w = ws(sid), cmp = w.cards.get(cardId);
  if (!cmp || cmp.kind !== "compare") throw new Error("compare card not found");
  const G: Group[] = [];
  for (const cid of cmp.sources) {
    const card = w.cards.get(cid); if (!card || card.kind !== "table") continue;
    const t = table(card.table_id!);
    let g = G.find((g) => g.company === t.company && g.basis === t.basis);
    if (!g) G.push(g = { company: t.company, basis: t.basis, cards: [] });
    g.cards.push([card, t]);
  }
  const used = G.map(() => new Set<string>());
  const lines: CompareLine[] = [], rejected: string[] = [], rej = G.map(() => new Map<string, string>());
  const unmatched: CompareLine[] = [];
  for (const c of CONCEPTS) {
    const hit = G.map(() => [] as string[]);
    const picked = G.map((g, gi) => {
      const ms = c.members[g.company] ?? [];
      for (const [card, t] of g.cards) for (const r of card.rows)
        if (ms.some((m) => alts(m).some((x) => memberMatches(x, t, r)))) hit[gi].push(`${card.card_id}|${r}`);
      return ms.map((m) => find(g, m)).filter((p) => p !== null);
    });
    const vals = picked.map((parts) => parts.length ? parts.map((p) => p.value) : null);
    // 사용자가 거부한 대응: 거부한 회사가 하나라도 있으면 이 개념으로 묶지 않음 → 행은 아래 "대응 없음"으로
    const decided = G.map((g) => w.mappings.get(`${c.id}|${g.company}`)?.accepted);
    if (decided.includes(false) && hit.some((keys) => keys.length)) { rejected.push(c.name); hit.forEach((ks, gi) => ks.forEach((k) => rej[gi].set(k, c.id))); continue; }
    if (vals.filter(Boolean).length < (G.length > 1 ? 2 : 1)) continue;
    picked.forEach((parts, gi) => parts.forEach((p) => used[gi].add(p.key)));
    const labels = new Set(vals.filter(Boolean).map((ps) => norm(ps![0].label.split("(")[0])));
    const same = vals.every((ps) => !ps || ps.length === 1) && labels.size === 1;
    const confirmed = vals.every((ps, gi) => !ps || decided[gi] === true);
    lines.push({ label: c.name, why: same ? "동일 계정명" : "대응 사전", values: vals, concept_id: c.id, note: c.note ?? null, confirmed });
  }
  // 사전 밖: 정규화한 계정명 + 구간이 같으면 대응
  const byName = new Map<string, (Part[] | null)[]>();
  G.forEach((g, gi) => { for (const [card, t] of g.cards) for (const r of card.rows) {
    const row = t.rows[r];
    if (used[gi].has(`${card.card_id}|${r}`) || row.values.every((v) => v == null)) continue;
    if (rej[gi].has(`${card.card_id}|${r}`)) { const vals: (Part[] | null)[] = G.map(() => null); vals[gi] = [part(card, t, r, row.label)];
      unmatched.push({ label: row.label, why: "거부한 대응", values: vals, concept_id: rej[gi].get(`${card.card_id}|${r}`)!, note: null }); continue; }
    const key = norm(row.label) + "|" + row.grp;
    if (!byName.has(key)) byName.set(key, G.map(() => null));
    const slot = byName.get(key)!;
    const value = [part(card, t, r, row.label)];
    if (!slot[gi]) slot[gi] = value;
    else {
      const values: (Part[] | null)[] = G.map(() => null);
      values[gi] = value;
      unmatched.push({ label: row.label, why: "대응 없음", values, concept_id: null, note: null });
    }
  } });
  for (const vals of byName.values()) {
    const label = vals.find(Boolean)![0].label;
    if (G.length > 1 && vals.filter(Boolean).length >= 2) lines.push({ label, why: "동일 계정명", values: vals, concept_id: null, note: null });
    else unmatched.push({ label, why: "대응 없음", values: vals, concept_id: null, note: null });
  }
  // 계정이 하나도 안 겹치면 비교를 고집하지 않고 고른 표의 행을 그대로 다 보여 준다
  if (!lines.length) for (let i = unmatched.length - 1; i >= 0; i--)
    if (unmatched[i].why === "대응 없음") lines.unshift({ ...unmatched.splice(i, 1)[0], why: "표 그대로" });
  const warnings: string[] = [];
  if (new Set(G.map((g) => g.basis)).size > 1) warnings.push("연결과 별도 기준이 섞여 있습니다.");
  const units = new Set(G.flatMap((g) => g.cards.map(([, t]) => t.unit)));
  if (units.size > 1) warnings.push(`단위가 다른 표(${[...units].join(", ")})를 십억원으로 환산해 비교합니다.`);
  warnings.push("비교 시점 — " + G.map((g) => `${g.company}: ` + g.cards.map(([c, t]) => t.cols[c.vcol ?? 0]).join(", ")).join(" / "));
  for (const l of lines) if (l.why === "대응 사전" && l.note) warnings.push(`${l.label}: ${l.note}`);
  return { groups: G.map((g) => ({ company: g.company, basis: g.basis })),
    lines: lines.map((l) => ({ ...l, totals: l.values.map(total) })), unmatched, warnings, rejected };
}


// ---------- 컨텍스트 컴파일 (context/compiler.py 포팅 + 문서·메모·연결) ----------
// 변경 빈도가 낮은 층이 앞: L0 system → L1 출처 → L2 카드 내용 → L3 조작·연결 → L4 요청. 앞층이 같으면 prefix 캐시 재사용
const SYSTEM = `당신은 SKT 재무기획 담당자(FP&A)가 사업기획서·예산서를 쓰도록 돕는 보조입니다.
- 숫자를 쓸 때는 근거 ID를 함께 적습니다. 표 값은 [셀ID], 문서 수치는 [doc:문서ID#f번호], 문서 내용은 [doc:문서ID]입니다.
- 컨텍스트에 없는 숫자는 만들지 않습니다. 근거가 없는 항목은 "근거 부족"으로 적습니다.
- 값 유형을 나눠 적습니다: 공시(원본 숫자), 계산(코드로 낸 값), AI 해석.
- 연결/별도, 단위, 기간이 다르면 먼저 밝힙니다. 편집 레이어(L3)의 값은 사용자가 고친 값이므로 원본과 구분합니다.
- 메모(카드 메모, 메모 카드, 선 메모)는 팀원이 남긴 글입니다. 각 메모가 작성 지시인지, 근거(숫자 · 출처가 있는 사실)인지, 의견인지 먼저 가립니다.
  지시는 해당 근거를 어떻게 쓸지에만 반영하고, 의견은 검토할 주장으로 다루며 사실 근거로 쓰지 않습니다. 근거 메모도 카드의 공시 · 문서와 대조합니다.
- 카드 사이 선은 관계 종류가 정해져 있지 않습니다. 두 카드 내용을 보고 근거 · 설명 · 반박 중 무엇인지 판단합니다.
- 회사마다 계정명이 다를 수 있습니다. 아래 개념 사전과 사용자가 확정한 대응을 우선합니다.`;
const LAYERS = ["L0_system", "L1_sources", "L2_workspace", "L3_edits", "L4_task"];
const fmtN = (v: number | null | undefined) => v == null ? "" : Number.isInteger(v) ? String(v) : v.toFixed(4).replace(/0+$/, "");
const hash = (s: string) => { let h = 0x811c9dc5; for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 0x01000193); return (h >>> 0).toString(16).padStart(8, "0"); };
const docKind = { ir: "IR", ar: "AR", news: "뉴스", internal: "사내" } as const;
// 데모 접수 ID별 원자료 공개일. KT 특수관계자 표도 같은 분기보고서 주석을 참조한다.
const filingDates = new Map([
  // https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260515002674
  ["KT_2026Q1", "2026-05-15"],
  ["KT_RP_2026Q1", "2026-05-15"],
  // https://www.sktelecom.com/img/eng/qua/20260507/1Q26InvestorBriefingENG.pdf
  ["SKT_2026Q1", "2026-05-07"],
  // https://news.lguplus.com/?p=21797
  ["LGU_2026Q1", "2026-05-07"],
]);
export function tableColumnWithinQuarter(t: SourceTable, col: number, cutoff?: number): boolean {
  if (cutoff === undefined) return true;
  const publishedDate = filingDates.get(t.rcept_no);
  if (!publishedDate || !withinQuarter(publishedDate, cutoff)) return false;
  const filingPeriod = /_(\d{4})Q([1-4])$/.exec(t.rcept_no);
  const quarter = quarterIndex(t.cols[col]) ?? (filingPeriod ? Number(filingPeriod[1]) * 4 + Number(filingPeriod[2]) : undefined);
  return quarter === undefined || quarter <= cutoff;
}

function compile(w: Workspace, task: string, cutoff?: number) {
  const cards = [...w.cards.values()].sort((a, b) => a.seq - b.seq);
  const tcards = cards.filter((c) => {
    if (c.kind !== "table") return false;
    if (cutoff === undefined) return true;
    const t = table(c.table_id!);
    return c.cols.some((col) => tableColumnWithinQuarter(t, col, cutoff));
  });
  const dcards = cards.filter((c) => c.kind === "doc" && docs.has(c.doc_id!) && withinQuarter(docs.get(c.doc_id!)!.date, cutoff));
  const L: Record<string, string> = {};
  L.L0_system = SYSTEM + "\n\n# 개념 사전\n" + CONCEPTS.map((c) => `- ${c.id} ${c.name}: ` + Object.entries(c.members).sort()
    .map(([co, ms]) => `${co}=` + ms.map((m) => m.label + (m.grp ? `(${m.grp})` : "")).join("+")).join("; ")).join("\n");

  const filings = [...new Set(tcards.map((c) => { const t = table(c.table_id!); return `- 공시 ${t.rcept_no} ${t.company}`; }))].sort();
  const dsrc = [...new Set(dcards.map((c) => c.doc_id!))].sort().map((id) => { const d = docs.get(id)!;
    return `- doc:${id} | ${docKind[d.kind]} | ${d.publisher} | ${d.date} | ${d.url || "사내 보관"}`; });
  L.L1_sources = filings.length + dsrc.length ? "# 출처\n" + [...filings, ...dsrc].join("\n") : "";

  const blocks = tcards.map((c) => {
    const t = table(c.table_id!);
    const cols = c.cols.filter((col) => tableColumnWithinQuarter(t, col, cutoff));
    const rows = c.rows.map((r) => { const row = t.rows[r]; return `r${r}\t${row.label}${row.grp ? `(${row.grp})` : ""}\t` + cols.map((k) => fmtN(row.values[k])).join("\t"); });
    return [`## 카드 ${c.card_id} | 공시 | ${t.company} | ${t.title} | ${t.basis} | 단위 ${t.unit} | 표 ${t.table_id}`,
      `(셀ID 형식: ${t.table_id}#r<행>c<열>, 열 번호 [${cols}])`, "셀ID\t계정\t" + cols.map((k) => t.cols[k]).join("\t"), ...rows].join("\n");
  });
  for (const c of dcards) {
    const d = docs.get(c.doc_id!)!;
    blocks.push([`## 카드 ${c.card_id} | ${docKind[d.kind]} | ${d.company} | ${d.title} | doc:${d.id}`, `요약: ${d.summary}`,
      ...d.figures.flatMap((f, i) => withinQuarter(f.period, cutoff) ? [`f${i}\t${f.label}\t${fmtN(f.value)} ${f.unit}\t${f.period}`] : [])].join("\n"));
  }
  for (const c of cards.filter((c) => c.kind === "memo" && c.text?.trim())) blocks.push(`## 메모 카드 ${c.card_id} | 작성 ${c.author}\n${c.text}`);
  for (const c of cards) {
    const panel = c.kind === "dash" ? panelOf(c.panel!) : undefined;
    if (!panel) continue;
    const t = tableOf(panel), cols = dashColumnIndices(t, cutoff);
    if (!cols.length) continue;
    const unit = t.unit ? ` | 단위 ${t.unit}` : "";
    const title = cols.length < t.cols.length ? panel.title.replace(/ \([^)]*\)$/, "") : panel.title;
    const heading = panel.ai && !withinQuarter("2025", cutoff) ? panel.sub : [title, panel.sub].filter(Boolean).join(" | ");
    blocks.push(`## 카드 ${c.card_id} | 공시 대시보드 | ${heading}${unit} | dash:${panel.id}\n${dashText(t, cols)}`);
  }
  L.L2_workspace = blocks.length ? "# 캔버스 카드\n" + blocks.join("\n\n") : "";

  const ed: string[] = [];
  for (const c of tcards) {
    const t = table(c.table_id!);
    if (c.vcol != null && tableColumnWithinQuarter(t, c.vcol, cutoff)) ed.push(`- 카드 ${c.card_id} 비교 기간: ${t.cols[c.vcol]} (c${c.vcol})`);
    for (const k of Object.keys(c.edits).sort()) { const [r, col] = k.split(",").map(Number);
      if (tableColumnWithinQuarter(t, col, cutoff)) ed.push(`- 수정 [${cellId(t, r, col)}] ${t.rows[r].label}: ${fmtN(t.rows[r].values[col])} → ${fmtN(c.edits[k])}`); }
    if (c.sort) ed.push(`- 카드 ${c.card_id} 정렬 기준: ${c.sort[0]} ${c.sort[1] ? "내림차순" : "오름차순"}`);
  }
  for (const m of [...w.mappings.values()].sort((a, b) => (a.concept_id + a.company).localeCompare(b.concept_id + b.company)))
    ed.push(`- 사용자 ${m.accepted ? "확정" : "거부"} 대응 ${m.concept_id} (${m.company}): ${m.cells.join(", ")}`);
  for (const c of cards.filter((c) => c.kind === "compare")) ed.push(`- ${c.view === "chart" ? "시각화" : "비교"} 카드 ${c.card_id}: ${c.sources.join(", ")}`);
  for (const l of [...w.links.values()].sort((a, b) => (a.source + a.target).localeCompare(b.source + b.target)))
    ed.push(`- 연결 ${l.source} — ${l.target}`);
  // 메모: 카드 메모, 선 메모 (메모 카드는 L2). 지시 · 근거 · 의견 구분은 에이전트가
  const pr: string[] = [];
  for (const [id, t] of [...w.memos].sort()) pr.push(`- 카드 ${id} 메모: ${t}`);
  for (const l of w.links.values()) if (l.memo) pr.push(`- 연결 ${l.source} — ${l.target} 메모: ${l.memo}`);
  if (pr.length) ed.push("", "# 메모", ...pr);
  L.L3_edits = ed.length ? "# 사용자 조작과 연결\n" + ed.join("\n") : "";
  L.L4_task = task ? `# 요청\n${task}` : "";
  return { layers: L, hashes: Object.fromEntries(LAYERS.map((k) => [k, hash(L[k] ?? "")])) };
}

export function context(sid: string, task: string, cutoff?: number): Context {
  const { layers: L, hashes } = compile(ws(sid), task, cutoff);
  const join = (ks: string[]) => ks.map((k) => L[k]).filter(Boolean).join("\n\n");
  return { layers: L, hashes, messages: [{ role: "system", content: join(LAYERS.slice(0, 3)) }, { role: "user", content: join(LAYERS.slice(3)) }] };
}

// ---------- 근거 추출: 카드 → 개념 태그가 붙은 근거 줄 (AI 초안·질문의 입력) ----------
const rowTags = (t: SourceTable, r: number) =>
  CONCEPTS.filter((c) => (c.members[t.company] ?? []).some((m) => alts(m).some((a) => memberMatches(a, t, r)))).map((c) => c.id);

export function evidence(sid: string, cutoff?: number): Evidence[] {
  const out: Evidence[] = [];
  for (const c of [...ws(sid).cards.values()].sort((a, b) => a.seq - b.seq)) {
    if (c.kind === "table") {
      const t = table(c.table_id!), col = c.vcol ?? c.cols[0];
      if (!tableColumnWithinQuarter(t, col, cutoff)) continue;
      for (const r of c.rows) for (const tag of rowTags(t, r)) {
        const v = value(c, t, r, col), edited = `${r},${col}` in c.edits;  // 편집 레이어 값은 원본 공시와 구분
        out.push({ tag, company: t.company, label: t.rows[r].label, value: v == null ? null : +(v * UNIT_TO_BIL[t.unit]).toFixed(1), unit: "십억원",
          period: t.cols[col], cite: cellId(t, r, col), card_id: c.card_id, source: "공시", edited });
      }
    } else if (c.kind === "dash") {
      for (const e of dashEvidence(c.panel!, cutoff)) out.push({ ...e, card_id: c.card_id, source: "공시" });
    } else if (c.kind === "doc" && docs.has(c.doc_id!)) {
      const d = docs.get(c.doc_id!)!;
      if (!withinQuarter(d.date, cutoff)) continue;
      for (const tag of d.tags) {
        out.push({ tag, company: d.company, label: d.title, value: null, unit: "", period: d.date, cite: `doc:${d.id}`, card_id: c.card_id, source: docKind[d.kind], text: d.summary });
        d.figures.forEach((f, i) => { if (withinQuarter(f.period, cutoff)) out.push({ tag, company: d.company, label: f.label, value: f.value, unit: f.unit, period: f.period,
          cite: `doc:${d.id}#f${i}`, card_id: c.card_id, source: docKind[d.kind] }); });
      }
    }
  }
  return out;
}

// 카드별 개념 태그 (같은 개념끼리 캔버스에 점선 자동 연결)
export function cardTags(sid: string): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  for (const e of evidence(sid)) (out[e.card_id] ??= []).includes(e.tag) || out[e.card_id].push(e.tag);
  return out;
}

// ---------- 자료 찾기: 질문을 낱말로 나눠 회사 필터 + 낱말별 검색 (임베딩 검색 자리) ----------
const COS: [RegExp, string][] = [[/^(skt|sk텔레콤)/i, "SKT"], [/^(kt(?![a-z])|케이티)/i, "KT"], [/^(lgu|lg유플러스|엘지)/i, "LGU+"]];
const STOP = new Set(["이유", "얼마", "어떻게", "무엇", "알려줘", "비교", "추이", "얼마나", "어때"]);
export function parseQuestion(q: string) {
  const companies: string[] = [], words: string[] = [];
  for (let w of q.replace(/\bLG\s+U\+/gi, "LGU+").replace(/[?？!.,·]/g, " ").split(/\s+/).filter(Boolean)) {
    const co = COS.find(([re]) => re.test(w)); if (co) { companies.includes(co[1]) || companies.push(co[1]); continue; }
    if (w.length >= 3) w = w.replace(/(으로|에서|이유는|은|는|이|가|을|를|의|에|와|과|도|로)$/, "");  // "감가"처럼 두 글자 낱말은 조사로 보지 않음
    if (w.length >= 2 && !STOP.has(w)) words.push(w);
  }
  return { companies, words };
}
// 공시 표: 낱말마다 search()를 돌려 표 단위로 합친다. 회사가 나오면 그 회사 표만
export function searchQuestion(q: string): SearchResult[] {
  const { companies, words } = parseQuestion(q);
  const byTable = new Map<string, SearchResult>();
  for (const w of words.length ? words : [q]) for (const r of search(w)) {
    const e = byTable.get(r.table_id);
    if (!e) byTable.set(r.table_id, { ...r, hits: [...r.hits] });
    else { e.score += r.score; for (const h of r.hits) if (!e.hits.some((x) => x.r === h.r)) e.hits.push(h); }
  }
  return [...byTable.values()].filter((r) => !companies.length || companies.includes(r.company)).sort((a, b) => b.score - a.score);
}
const COMMON = /3사|업계/;
export const tagsFor = (w: string) => {
  const nq = norm(w);
  return new Set([...CONCEPTS.filter((c) => [c.name, ...(c.alias ?? [])].some((a) => { const na = norm(String(a)); return na.length >= 2 && (nq.includes(na) || na.includes(nq)); })).map((c) => c.id),
    ...Object.entries(analysis.tag_names).filter(([, n]) => norm(n).includes(nq) || nq.includes(norm(n))).map(([id]) => id)]);
};
// 질문 낱말이 닿는 개념 ID (3사 비교 AI 분석 거르기용)
export const conceptsFor = (q: string) => new Set(parseQuestion(q).words.flatMap((w) => [...tagsFor(w)]));
// 문서: 낱말이 본문에 있으면 2점, 개념 태그로 닿으면 1점. 회사가 나오면 그 회사와 3사 공통 자료만
export function searchDocs(q: string, kinds: Doc["kind"][]) {
  const pool = [...docs.values()].filter((d) => kinds.includes(d.kind));
  const { companies, words } = parseQuestion(q);
  if (!words.length && !companies.length) return pool.sort((a, b) => b.date.localeCompare(a.date)).map((doc) => ({ doc, why: "" }));
  return pool.flatMap((doc) => {
    if (companies.length && !companies.includes(doc.company) && !COMMON.test(doc.company)) return [];
    let s = 0; const why: string[] = [];
    for (const w of words) {
      if (norm(doc.title + doc.summary).includes(norm(w))) { s += 2; why.push(`본문: ${w}`); continue; }
      const ts = tagsFor(w), t = doc.tags.find((x) => ts.has(x));
      if (t) { s += 1; why.push(`개념: ${analysis.tag_names[t] ?? t}`); }
    }
    if (words.length && !s) return [];
    return [{ doc, why: why.join(" · ") || `회사: ${doc.company}`, s }];
  }).sort((a, b) => b.s - a.s || b.doc.date.localeCompare(a.doc.date)).map(({ doc, why }) => ({ doc, why }));
}

// ---------- 인사이트 추천: 캔버스의 회사 + 개념 태그와 겹치는 정도 ----------
export const tagName = (t: string) => analysis.tag_names[t] ?? CONCEPTS.find((c) => c.id === t)?.name ?? t;
export function recommend(companies: string[], tags: string[], kinds: Doc["kind"][]) {
  const name = tagName;
  return [...docs.values()].filter((d) => kinds.includes(d.kind)).map((doc) => {
    const co = companies.includes(doc.company) ? 2 : COMMON.test(doc.company) ? 1 : 0, hit = doc.tags.filter((t) => tags.includes(t));
    const why = [co === 2 ? `회사 ${doc.company}` : co ? "3사 공통" : "", hit.length ? `개념 ${hit.map(name).join(", ")}` : ""].filter(Boolean).join(" · ");
    return { doc, why, s: co + hit.length };
  }).sort((a, b) => b.s - a.s || b.doc.date.localeCompare(a.doc.date)).map(({ doc, why, s }) => ({ doc, why, s }));
}
// 3사 비교 사실 표: 개념마다 회사별 1Q26·1Q25 값(십억원). 열 이름으로 기간을 맞춘다 (CSV 1Q26, KT XML 당분기·제 10 기 1분기)
const CUR = /1Q26|당분기|제 10 기 1분기/, PRV = /1Q25|전분기|제 9 기 1분기/;
export const PEERS = ["SKT", "KT", "LGU+"];
export function peerFacts(conceptIds: string[], cutoff?: number) {
  const tables = new Set<string>();
  // 공시 표에 없는 개념(설비투자 등 문서 태그)은 건너뜀
  const facts = conceptIds.flatMap((id) => CONCEPTS.filter((x) => x.id === id)).map((c) => {
    const id = c.id;
    return { concept: id, name: c.name, rows: PEERS.map((co) => {
      const sum = (re: RegExp) => {
        let tot: number | null = null;
        for (const m of c.members[co] ?? []) for (const t of TABLES.values()) {
          if (t.company !== co) continue;
          const col = t.cols.findIndex((x, i) => re.test(x) && tableColumnWithinQuarter(t, i, cutoff));
          if (col < 0) continue;
          const r = t.rows.findIndex((_, i) => alts(m).some((a) => memberMatches(a, t, i))); if (r < 0) continue;
          const v = t.rows[r].values[col]; if (v == null) continue;
          tot = (tot ?? 0) + v * UNIT_TO_BIL[t.unit]; if (re === CUR) tables.add(t.table_id); break;
        }
        return tot == null ? null : +tot.toFixed(1);
      };
      return { company: co, cur: sum(CUR), prv: sum(PRV) };
    }) };
  });
  return { facts, tables: [...tables] };
}
