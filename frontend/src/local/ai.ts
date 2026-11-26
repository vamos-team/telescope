// 규칙 기반 목업 AI. 실제 서비스에서는 context()의 층을 LLM에 보내 검토 · 질문을 만들고, 응답의 근거 ID를 서버가 검증한다.
// 여기서는 같은 입력(캔버스 카드 · 연결 · 메모 + data/demo/analysis.json 양식)으로 항상 같은 결과를 만든다.
// 분석하기 = ① 검토(메모 분류 · 주장 추출 · 근거 충분/부족 · 점검 지적 · AI 인사이트) → ② 예상 질문(애널리스트 리포트) → ③ 평가(다음 분기 자료가 들어온 뒤)
import { api, docTitle, UNIT_TO_BIL, type Analysis, type Evidence, type Doc } from "../api";
import { panelOf, tableOf } from "./dashboard";
import { ANNUAL_INSIGHTS } from "./dart";
import { tagsFor } from "./server";
import { checkUpload, COMPANIES4 } from "./validate";
import type { Card, Link, MemoCard, Note } from "../stores/canvas";

export interface Question { topic: string; q: string; why: string; level: string; cite: string[]; tags?: string[] }
export interface Assumption { tag: string; name: string; value: number; cite: string; period: string }
export type MemoKind = "지시" | "근거" | "의견";
export interface MemoLine { key: string; kind: MemoKind; text: string; where: string; author?: string; cardId?: string; linkId?: string }
export type Verdict = "충분" | "보통" | "부족";
export interface Claim { id: string; text: string; from: string; source: string; cardId?: string; tags: string[]; company?: string; verdict: Verdict;
  evidence: { cite: string; label: string; source: string; fit?: string; ok?: boolean }[]; need?: string }
export interface Finding { cardId: string; level: "bad" | "warn"; text: string; ref: string }
export interface InsightItem { id: string; title: string; facts: { text: string; cite?: string }[]; ai: string; news: string[] }
export interface Review { memos: MemoLine[]; claims: Claim[]; overall: { verdict: Verdict; text: string }; findings: Finding[]; insights: InsightItem[]; note: string }
export type Evaluation = ReturnType<typeof evaluate>;
// 분석 세션: 분석하기를 누를 때마다 하나씩 쌓임. 평가는 다음 분기 자료가 들어온 뒤 붙음
// base: 업데이트로 만든 버전이면 처음 세션의 ID (같은 세션의 버전끼리 묶음). 결과는 고치지 않고 버전으로 쌓는다
export interface Session { id: string; base?: string; at: string; sig: string; cards: number; quarter: string; review: Review; questions: Question[]; assume: Assumption[]; eval: Evaluation | null }

const CUR = /1Q26|당분기|제 10 기 1분기/;  // 기준 분기 열 (server.ts peerFacts와 같은 규칙)
const num = (v: number) => v.toLocaleString("ko-KR", { maximumFractionDigits: 1 });
const pctTxt = (a: number, b: number) => `${b >= a ? "+" : ""}${((b - a) / Math.abs(a) * 100).toFixed(1)}%`;
const SRC_OF: Record<Doc["kind"], string> = { ir: "IR", ar: "AR", news: "뉴스", internal: "사내" };

export interface NoteInput { canvas: string; quarter: string; cards: Card[]; links: Link[]; memos: MemoCard[]; notes: Note[]; sessionId: string;
  title: (id: string) => string }

// ---------- 메모 분류: 문장마다 지시 · 근거 · 의견 ----------
const ORDER = /(할 것|말 것|쓸 것|넣을 것|뺄 것|해\s?줘|해\s?주세요|하세요|하자|바람|필요|보강|확인할|정리할|강조할|반영할|구분할)[.!]?$/;
const FACT = /-?\d[\d,]*(\.\d+)?\s*(조원|억원|십억원|백만원|원|억|조|%|장|건|명)|공시|사업보고서|IR|리포트|기사|에 따르면|발표/;
export function classify(text: string): MemoKind { const t = text.trim(); return ORDER.test(t) ? "지시" : FACT.test(t) ? "근거" : "의견"; }
const sentences = (t: string) => t.split(/(?<=[.!?。])\s+|\n+/).map((x) => x.trim()).filter(Boolean);
const tagsOf = (t: string) => [...new Set(t.replace(/[.,·()]/g, " ").split(/\s+/).filter((w) => w.length >= 2).flatMap((w) => [...tagsFor(w.replace(/(은|는|이|가|을|를|의|에|와|과|도|로|부터|까지)$/, ""))]))];

// ---------- 캔버스 정리 글 (검토 화면 아래 접기 섹션, 복사 · Markdown) ----------
export async function contextNote(x: NoteInput): Promise<string> {
  const hint = (id: string) => x.notes.filter((n) => n.kind === "card" && n.cardId === id).map((n) => `  - 메모: ${n.text}`);
  const by = (k: Card["kind"]) => x.cards.filter((c) => c.kind === k);
  const docs = by("doc").flatMap((c) => { const d = c.kind === "doc" ? api.docs.get(c.docId) : undefined; return d ? [{ c, d }] : []; });
  const count = [["공시 표", by("table").length], ["대시보드", by("dash").length], ["IR·AR", docs.filter(({ d }) => d.kind === "ir" || d.kind === "ar").length],
    ["뉴스", docs.filter(({ d }) => d.kind === "news").length], ["사내 자료", docs.filter(({ d }) => d.kind === "internal").length], ["비교", by("compare").length], ["메모", x.notes.length]]
    .filter(([, n]) => n).map(([l, n]) => `${l} ${n}`).join(", ");
  const out: string[] = [`# ${x.canvas} 캔버스 정리`, "",
    `기준 시점 ${x.quarter}. 카드 ${x.cards.length}장(${count || "없음"}), 연결 ${x.links.length}개.`,
    "이 글은 캔버스의 카드 · 연결 · 메모를 글로 옮긴 컨텍스트입니다. 숫자는 카드 값을 그대로 옮겼고, [ ] 안은 근거 ID입니다."];
  const sec = (title: string, body: string[]) => { if (body.length) out.push("", `## ${title}`, ...body); };

  const tb: string[] = [];
  for (const c of by("table")) {
    if (c.kind !== "table") continue;
    const t = await api.table(c.tableId), col = c.vcol, k = UNIT_TO_BIL[t.unit] ?? 1;
    tb.push("", `### ${t.company} ${t.title} (${[t.basis, t.cols[col]].filter(Boolean).join(", ")}, 단위 ${t.unit})`);
    for (const r of c.rows) {
      const row = t.rows[r]; if (!row) continue;
      const key = `${r},${col}`, edited = key in c.edits, v = edited ? c.edits[key] : row.values[col];
      if (v == null && !edited) continue;  // 값 없는 머리 행(부채 · 자본 등)은 건너뜀
      tb.push(`- ${row.label}: ${v == null ? "값 없음" : `${num(v)}${t.unit}${t.unit !== "십억원" ? ` (${num(v * k)}십억원)` : ""}`}${edited ? ", 사용자 수정값" : ""} [${t.table_id}#r${r}c${col}]`);
    }
    tb.push(...hint(c.id));
  }
  sec("공시 재무", tb);

  const db: string[] = [];
  for (const c of by("dash")) {
    const p = c.kind === "dash" ? panelOf(c.panel) : undefined; if (!p) continue;
    const t = tableOf(p), lastCol = t.cols.length - 1;
    db.push("", `### ${p.title}${p.sub ? ` (${p.sub})` : ""}`);
    for (const r of t.rows.slice(0, 12)) {
      const v = r.v[lastCol] ?? r.v[r.v.length - 1];
      db.push(`- ${r.n}: ${typeof v === "number" ? num(v) + (t.unit && !/%$/.test(String(v)) ? t.unit : "") : v}${t.cols.length > 1 ? ` (${t.cols[lastCol]})` : ""} [dash:${p.id}#${encodeURIComponent(r.n)}]`);
    }
    if (p.ai) db.push(`- AI 해석(사실 아님): ${p.ai}`);
    db.push(...hint(c.id));
  }
  sec("DART 대시보드", db);

  const cb: string[] = [];
  for (const c of by("compare")) {
    try {
      const r = await api.compare(x.sessionId, c.id);
      cb.push("", `### 계정 대응 비교 (${r.groups.map((g) => g.company).join(" · ")})`);
      for (const l of r.lines) cb.push(`- ${l.label}: ${l.values.map((ps, i) => `${r.groups[i].company} ${ps ? num(ps.reduce((a, p) => a + (p.value_bil ?? 0), 0)) : "—"}`).join(" / ")}십억원${l.confirmed ? " (사용자 확정)" : l.why === "대응 사전" ? " (계정명이 달라 대응 사전으로 맞춤)" : ""}`);
      if (r.unmatched.length) cb.push(`- 대응 없음: ${r.unmatched.map((l) => l.label).join(", ")}`);
      cb.push(...hint(c.id));
    } catch { /* 비교 원본이 지워진 카드 */ }
  }
  sec("계정 대응 비교", cb);

  const docSec = (title: string, kinds: string[]) => sec(title, docs.filter(({ d }) => kinds.includes(d.kind)).flatMap(({ c, d }) => [
    "", `### ${docTitle(d)} (${d.publisher}, ${d.date})`,
    ...(d.summary && d.summary !== d.title ? [`${d.summary} [doc:${d.id}]`] : [`${d.title} [doc:${d.id}]`]),
    ...d.figures.map((f, i) => `- ${f.label}: ${num(f.value)}${f.unit} (${f.period}) [doc:${d.id}#f${i}]`), ...hint(c.id)]));
  docSec("IR · AR", ["ir", "ar"]); docSec("뉴스", ["news"]); docSec("사내 자료", ["internal"]);

  sec("카드 사이 연결", x.links.map((l) => { const n = x.notes.find((y) => y.linkId === l.id);
    return `- ${x.title(l.source)} — ${x.title(l.target)}${n ? `. 메모: ${n.text}` : ""}`; }));
  sec("메모 카드", x.notes.filter((n) => n.kind === "memo").map((n) => `- ${n.author}: ${n.text} (연결: ${n.where})`));
  return out.join("\n") + "\n";
}

// ---------- 검토 ----------
export interface ReviewInput extends NoteInput { ev: Evidence[]; tags: Record<string, string[]> }
export async function review(a: Analysis, x: ReviewInput): Promise<Review> {
  const name = (t: string) => a.tag_names[t] ?? t;
  const linked = (id: string) => new Set(x.links.filter((l) => l.source === id || l.target === id).map((l) => l.source === id ? l.target : l.source));

  // 1. 메모 분류 (문장 단위)
  const memos: MemoLine[] = x.notes.flatMap((n) => sentences(n.text).map((t, i) => ({ key: `${n.key}.${i}`, kind: classify(t), text: t,
    where: n.kind === "card" ? `카드 메모 · ${n.where}` : n.kind === "link" ? `선 메모 · ${n.where}` : `메모 카드 · ${n.author}`, author: n.author, cardId: n.cardId, linkId: n.linkId })));

  // 2. 주장: 의견 메모 문장 + 문서 카드 요약
  const claims: Claim[] = [];
  const judge = (c: Omit<Claim, "verdict" | "evidence" | "need">, near: Set<string>, ownSource?: string): Claim => {
    // 근거: 주장과 같은 개념(태그)의 줄. 태그가 없으면 이어진 카드의 줄. 주장 회사가 있으면 그 회사(SKB는 SKT 연결에 포함) 값만
    const coOk = (co: string) => !c.company || co === c.company || (c.company === "SKB" && co === "SKT") || /3사|업계/.test(co);
    const es = x.ev.filter((e) => e.card_id !== c.cardId && (c.tags.length ? c.tags.includes(e.tag) : near.has(e.card_id)) && coOk(e.company));
    const uniq = [...new Map(es.map((e) => [e.cite, e])).values()];
    // 근거 하나하나 평가: 주장과 어떻게 맞닿는지 (코드 규칙. LLM으로 바꿔도 같은 자리)
    const fit = (e: Evidence): [string, boolean] => e.edited ? ["사용자 수정값이라 원본 대조 필요", false]
      : e.value == null ? ["정성 근거 (숫자 없음)", false]
      : c.company && e.company !== c.company && !(c.company === "SKB" && e.company === "SKT") ? ["다른 회사 값", false]
      : !c.company && e.company !== a.focus ? [`${e.company} 비교 값 (주장 대상과 다름)`, false]
      : c.tags.includes(e.tag) && near.has(e.card_id) ? ["같은 계정 · 연결된 카드, 직접 근거", true]
      : c.tags.includes(e.tag) ? ["같은 계정의 공시 값, 직접 근거", true]
      : ["연결된 카드의 값, 간접 근거", false];
    // 판정은 근거별 평가에서 "직접 근거"로 본 것만 셈: 직접 근거인 공시 숫자 + 다른 출처가 함께 있으면 충분, 직접 근거만 있거나 간접 · 비교 값뿐이면 보통, 없으면 부족
    const direct = uniq.filter((e) => fit(e)[1]);
    const kinds = new Set([...direct.map((e) => e.source), ...uniq.filter((e) => e.source !== "공시").map((e) => e.source), ...(ownSource ? [ownSource] : [])]);
    const verdict: Verdict = direct.some((e) => e.source === "공시") && kinds.size >= 2 ? "충분" : direct.length || uniq.some((e) => e.value != null) ? "보통" : "부족";
    const need = verdict === "충분" ? undefined : !c.tags.length && !near.size ? "이 주장과 이어진 카드가 없습니다. 근거가 되는 카드를 선으로 이으세요."
      : verdict === "부족" ? `${c.tags.map(name).join(" · ") || "관련 계정"} 공시 표나 IR 수치를 캔버스에 올리세요.`
      : "공시 숫자와 다른 출처(IR · 뉴스 · 사내 자료)를 하나 더 이으면 근거가 충분해집니다.";
    return { ...c, verdict, need, evidence: uniq.slice(0, 4).map((e) => { const [f, ok] = fit(e);
      return { cite: e.cite, label: `${e.company} ${e.label}${e.value != null ? ` ${num(e.value)}${e.unit}` : ""}`, source: e.source, fit: f, ok }; }) };
  };
  for (const m of memos.filter((m) => m.kind === "의견")) {
    const near = m.cardId ? linked(m.cardId) : new Set<string>();
    if (m.linkId) { const l = x.links.find((y) => y.id === m.linkId); if (l) { near.add(l.source); near.add(l.target); } }
    if (m.cardId && !x.cards.find((c) => c.id === m.cardId && c.kind === "memo")) near.add(m.cardId);
    const own = tagsOf(m.text), tags = own.length ? own : [...new Set([...near].flatMap((id) => x.tags[id] ?? []))];
    const company = COMPANIES4.find(([, re]) => re.test(m.text))?.[0];
    claims.push(judge({ id: m.key, text: m.text, from: `사용자 주장${m.author ? ` · ${m.author}` : ""}`, source: "사용자 주장", cardId: m.cardId, tags, company }, near));
  }
  for (const c of x.cards) {
    if (c.kind !== "doc") continue;
    const d = api.docs.get(c.docId); if (!d || !d.summary || d.summary === d.title) continue;
    const s = sentences(d.summary)[0];
    claims.push(judge({ id: "doc:" + c.id, text: s, from: `${SRC_OF[d.kind]} · ${docTitle(d)}`, source: SRC_OF[d.kind], cardId: c.id, tags: d.tags.filter((t) => tagsOf(s).includes(t)).length ? d.tags.filter((t) => tagsOf(s).includes(t)) : d.tags,
      company: /3사|업계/.test(d.company) ? undefined : d.company }, linked(c.id), SRC_OF[d.kind]));
  }
  const cnt = (v: Verdict) => claims.filter((c) => c.verdict === v).length;
  const overall = { verdict: (cnt("부족") ? "부족" : cnt("보통") ? "보통" : "충분") as Verdict,
    text: claims.length ? `주장 ${claims.length}개 중 근거 충분 ${cnt("충분")}, 보통 ${cnt("보통")}, 부족 ${cnt("부족")}.${cnt("부족") ? " 부족한 주장부터 근거를 보강하세요." : cnt("보통") ? " 숫자 근거는 있으나 출처가 한 종류인 주장이 있습니다." : " 모든 주장에 공시 숫자와 다른 출처가 함께 있습니다."}`
      : "검토할 주장이 없습니다. 의견 메모나 IR · 뉴스 카드를 올리면 주장으로 검토합니다." };

  // 3. 점검 지적 (캔버스 카드 박스)
  const findings: Finding[] = [];
  for (const c of claims) if (c.verdict === "부족" && c.cardId) findings.push({ cardId: c.cardId, level: "bad", text: "근거 부족", ref: c.id });
  for (const c of x.cards) {
    if (c.kind === "compare" || c.kind === "chart") {
      try { const r = await api.compare(x.sessionId, c.id);
        if (r.warnings.some((w) => /연결과 별도/.test(w))) findings.push({ cardId: c.id, level: "warn", text: "연결 · 별도 기준이 섞임", ref: "check" });
        if (r.warnings.some((w) => /단위가 다른/.test(w))) findings.push({ cardId: c.id, level: "warn", text: "단위가 다른 표를 환산함", ref: "check" });
      } catch { /* 원본이 지워짐 */ }
    }
    if (c.kind === "table" && Object.keys(c.edits).length) findings.push({ cardId: c.id, level: "warn", text: `사용자 수정값 ${Object.keys(c.edits).length}개`, ref: "check" });
    const inCmp = x.cards.some((k) => (k.kind === "compare" || k.kind === "chart") && (k.sources.includes(c.id) || k.id === c.id));
    if (!linked(c.id).size && !inCmp) findings.push({ cardId: c.id, level: "warn", text: "다른 카드와 연결 없음", ref: "check" });
  }

  // 4. AI 인사이트: 캔버스 개념과 맞는 3사 비교 · 연간 인사이트 + 카드 값 변화(코드 계산)
  const tags = new Set(Object.values(x.tags).flat());
  const insights: InsightItem[] = [];
  for (const i of a.insights.filter((i) => i.concepts.some((c) => tags.has(c)))) {
    const f = api.peerFacts(i.concepts.slice(0, 2)).facts;
    insights.push({ id: i.id, title: i.title, ai: i.ai, news: i.news,
      facts: f.flatMap((r) => r.rows.filter((y) => y.cur != null).map((y) => ({ text: `${y.company} ${r.name} ${num(y.cur!)}십억원${y.prv ? ` (전년 동기 대비 ${pctTxt(y.prv, y.cur!)})` : ""}` }))).slice(0, 6) });
  }
  const ANN: Record<string, string> = { ins_op: "opi", ins_capex: "capex", ins_div: "dividend" };
  const hasDash = x.cards.some((c) => c.kind === "dash");
  for (const i of ANNUAL_INSIGHTS) if (hasDash || tags.has(ANN[i.id])) insights.push({ id: i.id, title: i.t, ai: i.ai, news: [...i.news], facts: [{ text: i.ft }] });
  const moves: { text: string; cite: string; d: number }[] = [];
  for (const c of x.cards) {
    if (c.kind !== "table" || c.cols.length < 2) continue;
    const t = api.tableInfo(c.tableId), other = c.cols.find((k) => k !== c.vcol)!;
    for (const r of c.rows) {
      const row = t.rows[r], cur = row?.values[c.vcol], prv = row?.values[other];
      if (cur == null || prv == null || !prv || !(x.tags[c.id] ?? []).length) continue;
      moves.push({ text: `${t.company} ${row.label}: ${t.cols[other]} ${num(prv * UNIT_TO_BIL[t.unit])} → ${t.cols[c.vcol]} ${num(cur * UNIT_TO_BIL[t.unit])}십억원 (${pctTxt(prv, cur)})`,
        cite: `${t.table_id}#r${r}c${c.vcol}`, d: Math.abs((cur - prv) / prv) });
    }
  }
  if (moves.length) insights.unshift({ id: "moves", title: "캔버스 표에서 크게 움직인 계정", news: [],
    ai: "변화율이 큰 계정부터 보여 줍니다. 비율은 카드 값으로 코드에서 계산했고, 원인 해석은 이어진 IR · 뉴스 카드와 함께 확인해야 합니다.",
    facts: moves.sort((p, q) => q.d - p.d).slice(0, 5).map(({ text, cite }) => ({ text, cite })) });

  return { memos, claims, overall, findings, insights, note: await contextNote(x) };
}

export function questions(a: Analysis, ev: Evidence[]): Question[] {
  const tags = new Set(ev.map((e) => e.tag));
  return a.questions.filter((q) => q.tags.some((t) => tags.has(t)))
    .map((q) => ({ ...q, cite: [...new Set(ev.filter((e) => q.tags.includes(e.tag)).map((e) => e.cite))].slice(0, 3) }))
    .sort((x, y) => +(y.level === "높음") - +(x.level === "높음"));
}

export const topicName = (a: Analysis, q: Question) => a.tag_names[q.tags?.[0] ?? ""] ?? q.topic;
// 애널리스트 리포트 형식 Markdown (예상 질문)
export function reportMd(a: Analysis, s: Session, canvas: string) {
  const hi = s.questions.filter((q) => q.level === "높음");
  return [`# ${a.focus} 애널리스트 질문`, "", `페이지: ${canvas} · 기준 시점 ${s.quarter} · 작성 ${s.at.slice(0, 10)}`, "",
    `**요지** 예상 질문 ${s.questions.length}개, 중요도 높음 ${hi.length}개. ${[...new Set(hi.slice(0, 3).map((q) => topicName(a, q)))].join(" · ")} 쟁점이 먼저 나올 가능성이 큼.`, "",
    "## 핵심 쟁점", ...s.questions.map((q, i) => `${i + 1}. ${q.q} (${q.level})`), "",
    ...s.questions.flatMap((q, i) => [`## ${i + 1}. ${topicName(a, q)}`, `> ${q.q}`, `- 중요도: ${q.level}`, `- 질문 배경: ${q.why}`, `- 확인할 수치: ${q.cite.map((c) => `[${c}]`).join(" ") || "캔버스에 없음"}`, ""])].join("\n");
}

// 다음 분기 가정: 주 대상 회사의 기준 분기 공시 원본값을 그대로 이어 간다고 두고 평가에서 실제 실적과 대조
// 대상 계정은 analysis 양식들의 assume 태그 합집합. 사용자 수정값과 다른 기간 열(예: 전년 동기)은 쓰지 않음
export function assumptions(a: Analysis, ev: Evidence[]): Assumption[] {
  const tags = [...new Set(a.deliverables.flatMap((d) => d.assume))];
  return tags.flatMap((tag) => {
    const e = ev.find((x) => x.tag === tag && x.company === a.focus && x.source === "공시" && x.value != null && !x.edited && CUR.test(x.period));
    return e ? [{ tag, name: a.tag_names[tag] ?? tag, value: e.value!, cite: e.cite, period: e.period }] : [];
  });
}

// 다음 분기 평가: 질문은 리포트 쟁점과 같은 주제면 적중, 가정은 실제 실적과 차이율로 판정
export function evaluate(a: Analysis, qs: Question[], assume: Assumption[]) {
  const nq = a.next_quarter, issueTopics = new Set(nq.issues.map((i) => i.topic)), qTopics = new Set(qs.map((q) => q.topic));
  const hit = qs.filter((q) => issueTopics.has(q.topic)), off = qs.filter((q) => !issueTopics.has(q.topic));
  const missed = nq.issues.filter((i) => !qTopics.has(i.topic));
  const pct = (x: number, n: number) => (n ? Math.round((x / n) * 100) : 0);
  const checks = assume.filter((x) => nq.actuals[x.tag] != null).map((x) => {
    const actual = nq.actuals[x.tag], diff = ((x.value - actual) / actual) * 100;
    return { ...x, actual, diff, ok: Math.abs(diff) <= 5 };
  });
  return { hit: hit.map((q) => ({ q, issue: nq.issues.find((i) => i.topic === q.topic)! })), off, missed, checks,
    precision: pct(hit.length, qs.length), recall: pct(nq.issues.length - missed.length, nq.issues.length) };
}

// ponytail: 판정 규칙 확인용 자체 점검. 브라우저 콘솔에서 window.__aiSelfCheck() 로 실행
export function selfCheck(a: Analysis) {
  const ev: Evidence[] = [{ tag: "da", company: a.focus, label: "감가상각비", value: 590.7, unit: "십억원", period: "1Q26", cite: "x#r5c1", card_id: "c1", source: "공시" }];
  const qs = questions(a, ev), as = assumptions(a, ev), r = evaluate(a, qs, as);
  console.assert(qs.length === 1 && qs[0].topic === "da_trend", "질문: da 태그는 da_trend 하나");
  console.assert(r.precision === 0 && r.recall === 0, "평가: da_trend는 2Q 쟁점에 없음");
  console.assert(as.length === 1 && r.checks.length === (a.next_quarter.actuals.da != null ? 1 : 0), "가정: da 하나");
  console.assert(assumptions(a, [{ ...ev[0], edited: true }]).length === 0, "가정: 사용자 수정값 제외");
  console.assert(assumptions(a, [{ ...ev[0], period: "1Q25" }]).length === 0, "가정: 기준 분기 열만");
  console.assert(classify("이 근거로 반박 문단을 쓸 것") === "지시" && classify("1분기 CapEx 3,120억원") === "근거" && classify("AI DC 투자가 늦어질 듯") === "의견", "메모 분류");
  console.assert(checkUpload("a.exe", "SKT").code === "FORMAT_MISMATCH" && checkUpload("삼성전자 계획.txt", "반도체").code === "COMPANY_MISMATCH"
    && checkUpload("SKT 설비.txt", "설비투자").code === null && checkUpload("SKB 망.md", "").code === "FORMAT_MISMATCH", "업로드 검증");
  return "ok";
}

// ---------- 내보내기 ----------
// 프롬프트로 추출: 캔버스에서 나온 주장과 근거(컨텍스트)를 정리해 LLM에게 검증을 물어볼 수 있는 프롬프트
export function claimPrompt(a: Analysis, r: Review, canvas: string, quarter: string) {
  const L: string[] = [
    `당신은 ${a.focus} 재무기획 담당자의 근거 검토를 돕는 애널리스트입니다. 아래는 '${canvas}' 캔버스(기준 시점 ${quarter})에서 나온 주장과, 각 주장에 연결된 근거입니다.`,
    "", "## 규칙",
    "- 아래 컨텍스트에 있는 숫자만 쓰고, 없는 숫자는 만들지 마세요.",
    "- 숫자를 인용할 때는 [ ] 안의 근거 ID를 함께 적으세요.",
    `- 기준 시점(${quarter}) 이후 자료는 쓰지 마세요.`,
    "", "## 주장과 근거"];
  if (!r.claims.length) L.push("- (주장 없음)");
  r.claims.forEach((c, i) => {
    L.push("", `${i + 1}. "${c.text}" (출처: ${c.from}, 현재 판정: 근거 ${c.verdict})`);
    for (const e of c.evidence) L.push(`   - 근거: ${e.label} (${e.source}) [${e.cite}]`);
    if (!c.evidence.length) L.push("   - 근거: 캔버스에 없음");
  });
  const orders = r.memos.filter((m) => m.kind === "지시");
  if (orders.length) L.push("", "## 작성자 지시", ...orders.map((m) => `- ${m.text} (${m.where})`));
  L.push("", "## 캔버스 컨텍스트", "", r.note.trim(), "", "## 요청",
    "1. 각 주장이 위 근거로 뒷받침되는지 '충분 / 보통 / 부족'으로 다시 판단하고, 이유를 한두 문장으로 적어 주세요.",
    "2. 근거가 부족한 주장에는 추가로 확인해야 할 자료나 수치를 구체적으로 적어 주세요.",
    "3. 이 주장들을 두고 애널리스트가 물을 만한 질문을 3개 적어 주세요.");
  return L.join("\n") + "\n";
}
// Markdown으로 내보내기: 검토부터 질문(평가가 있으면 평가까지) 한 파일로
export function analysisMd(a: Analysis, s: Session, canvas: string) {
  const r = s.review, L: string[] = [`# ${canvas} 분석`, "", `기준 시점 ${s.quarter} · 근거 카드 ${s.cards}장 · 작성 ${s.at.slice(0, 10)}`, "",
    "## 1. 검토", "", `**총평** ${r.overall.text}`, "", "### 주장별 검토"];
  r.claims.forEach((c, i) => {
    L.push("", `#### ${i + 1}. ${c.from} — 근거 ${c.verdict}`, "", `> ${c.text}`, "");
    L.push(...(c.evidence.length ? c.evidence.map((e) => `- ${e.label} (${e.source}) [${e.cite}]`) : ["- 캔버스에 근거 없음"]));
    if (c.need) L.push(`- 보완: ${c.need}`);
  });
  if (r.findings.length) L.push("", "### 캔버스에 표시한 지적", ...r.findings.map((f) => `- ${f.cardId}: ${f.text}`));
  if (r.memos.length) L.push("", "### 메모 분류", ...r.memos.map((m) => `- [${m.kind}] ${m.text} (${m.where})`));
  if (r.insights.length) L.push("", "### AI 인사이트", ...r.insights.flatMap((i) => ["", `#### ${i.title}`, ...i.facts.map((f) => `- ${f.text}${f.cite ? ` [${f.cite}]` : ""}`), `- AI 해석: ${i.ai}`]));
  L.push("", "## 2. 질문", "");
  L.push(...(s.questions.length ? s.questions.flatMap((q, i) => [`### Q${i + 1}. ${topicName(a, q)} (중요도 ${q.level})`, "", `> ${q.q}`, "", `- 왜 묻나요: ${q.why}`,
    `- 미리 준비할 근거: ${q.cite.map((c) => `[${c}]`).join(" ") || "캔버스에 없음"}`, ""]) : ["질문을 만들 근거가 없습니다.", ""]));
  if (s.eval) {
    const e = s.eval;
    L.push("## 3. 평가", "", `정밀도 ${e.precision}% · 재현율 ${e.recall}%`, "",
      ...e.hit.map((h) => `- 맞힘: ${h.q.q} → ${h.issue.title} (${h.issue.publisher})`), ...e.off.map((q) => `- 빗나감: ${q.q}`),
      ...e.missed.map((m) => `- 놓친 쟁점: ${m.title} (${m.publisher})`),
      ...e.checks.map((c) => `- 가정 ${c.name}: ${c.value} → 실적 ${c.actual} (${c.diff > 0 ? "+" : ""}${c.diff.toFixed(1)}%, ${c.ok ? "유지" : "재검토"})`), "");
  }
  return L.join("\n");
}
