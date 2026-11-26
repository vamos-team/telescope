// 규칙 기반 목업 AI. 실제 서비스에서는 context()의 층을 LLM에 보내 글로 정리하고, 응답의 근거 ID를 서버가 검증한다.
// 여기서는 같은 입력(캔버스 카드 · 연결 · 메모 · 프롬프트 + data/demo/analysis.json 양식)으로 항상 같은 결과를 만든다.
// 생성하기 = ① 캔버스 정리 글(컨텍스트) → ② 예상 질문 → ③ 평가. 근거 부족 판정은 하지 않고, 캔버스에 있는 것만 글로 옮긴다
import { api, docTitle, UNIT_TO_BIL, type Analysis, type Evidence } from "../api";
import { panelOf, tableOf } from "./dashboard";
import type { Card, Link, MemoCard } from "../stores/canvas";

// 사용자 프롬프트: 카드 프롬프트(cardId), 선 프롬프트(linkId)
export interface UserPrompt { cardId?: string; linkId?: string; where: string; text: string }
export interface Question { topic: string; q: string; why: string; level: string; cite: string[] }
export interface Assumption { tag: string; name: string; value: number; cite: string; period: string }

const CUR = /1Q26|당분기|제 10 기 1분기/;  // 기준 분기 열 (server.ts peerFacts와 같은 규칙)
const num = (v: number) => v.toLocaleString("ko-KR", { maximumFractionDigits: 1 });
const josa = (w: string, a: string, b: string) => { const c = w.charCodeAt(w.length - 1); return c >= 0xac00 && c <= 0xd7a3 && (c - 0xac00) % 28 ? a : b; };
const REL_TXT: Record<string, string> = { 설명: "설명한다", 반박: "반박한다" };

export interface NoteInput { canvas: string; quarter: string; cards: Card[]; links: Link[]; memos: MemoCard[]; prompts: UserPrompt[]; sessionId: string;
  title: (id: string) => string }
// 캔버스 → Markdown 정리 글. 숫자는 카드 값을 코드로 옮기고(단위 환산 포함), [ ] 안에 근거 ID를 붙인다
export async function contextNote(x: NoteInput): Promise<string> {
  const hint = (id: string) => x.prompts.filter((p) => p.cardId === id).map((p) => `  - 지시: ${p.text}`);
  const by = (k: Card["kind"]) => x.cards.filter((c) => c.kind === k);
  const docs = by("doc").flatMap((c) => { const d = c.kind === "doc" ? api.docs.get(c.docId) : undefined; return d ? [{ c, d }] : []; });
  const count = [["공시 표", by("table").length], ["대시보드", by("dash").length], ["IR·AR", docs.filter(({ d }) => d.kind === "ir" || d.kind === "ar").length],
    ["뉴스", docs.filter(({ d }) => d.kind === "news").length], ["사내 자료", docs.filter(({ d }) => d.kind === "internal").length], ["비교", by("compare").length], ["메모", x.memos.length]]
    .filter(([, n]) => n).map(([l, n]) => `${l} ${n}`).join(", ");
  const out: string[] = [`# ${x.canvas} 캔버스 정리`, "",
    `기준 분기 ${x.quarter}. 카드 ${x.cards.length}장(${count || "없음"}), 연결 ${x.links.length}개.`,
    "이 글은 캔버스의 카드 · 연결 · 메모를 글로 옮긴 컨텍스트입니다. 숫자는 카드 값을 그대로 옮겼고, [ ] 안은 근거 ID입니다."];
  const sec = (title: string, body: string[]) => { if (body.length) out.push("", `## ${title}`, ...body); };

  // 공시 표: 고른 기간 열의 값. 사용자 수정값은 표시, 원본 단위와 십억원 환산을 함께
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

  // 대시보드 카드: 패널 표의 마지막 열 (DART 공시, 코드 계산 비율 포함)
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

  // 비교 카드: 개념별 회사 값(십억원)
  const cb: string[] = [];
  for (const c of by("compare")) {
    try {
      const r = await api.compare(x.sessionId, c.id);
      cb.push("", `### 계정 대응 비교 (${r.groups.map((g) => g.company).join(" · ")})`);
      for (const l of r.lines) cb.push(`- ${l.label}: ${l.values.map((ps, i) => `${r.groups[i].company} ${ps ? num(ps.reduce((a, p) => a + (p.value_bil ?? 0), 0)) : "—"}`).join(" / ")}십억원${l.why === "대응 사전" ? " (계정명이 달라 대응 사전으로 맞춤)" : ""}`);
      if (r.unmatched.length) cb.push(`- 대응 없음: ${r.unmatched.map((l) => l.label).join(", ")}`);
      for (const w of r.warnings) cb.push(`- 주의: ${w}`);
      cb.push(...hint(c.id));
    } catch { /* 비교 원본이 지워진 카드 */ }
  }
  sec("계정 대응 비교", cb);

  // 문서: 요약 + 핵심 수치
  const docSec = (title: string, kinds: string[]) => sec(title, docs.filter(({ d }) => kinds.includes(d.kind)).flatMap(({ c, d }) => [
    "", `### ${docTitle(d)} (${d.publisher}, ${d.date})`,
    ...(d.summary && d.summary !== d.title ? [`${d.summary} [doc:${d.id}]`] : [`${d.title} [doc:${d.id}]`]),
    ...d.figures.map((f, i) => `- ${f.label}: ${num(f.value)}${f.unit} (${f.period}) [doc:${d.id}#f${i}]`), ...hint(c.id)]));
  docSec("IR · AR", ["ir", "ar"]); docSec("뉴스", ["news"]); docSec("사내 자료", ["internal"]);

  // 연결: "A는 B의 근거가 된다" + 선 프롬프트
  sec("카드 사이 관계", x.links.map((l) => { const a = x.title(l.source), b = x.title(l.target), p = x.prompts.find((y) => y.linkId === l.id);
    const tail = l.rel === "근거" ? `${b}의 근거가 된다` : l.rel === "관련" ? `${b}${josa(b, "과", "와")} 관련이 있다` : `${b}${josa(b, "을", "를")} ${REL_TXT[l.rel] ?? l.rel}`;
    return `- ${a}${josa(a, "은", "는")} ${tail}.${p ? ` 지시: ${p.text}` : ""}`; }));
  // 메모: 의견이라 사실 근거로 쓰지 않음
  sec("팀 메모 · 보완 요청 (의견, 사실 근거 아님)", x.memos.map((m) => `- [${m.type === "request" ? "보완 요청" : "메모"}] ${m.author}: ${m.text.trim()}`));
  return out.join("\n") + "\n";
}

export function questions(a: Analysis, ev: Evidence[]): Question[] {
  const tags = new Set(ev.map((e) => e.tag));
  return a.questions.filter((q) => q.tags.some((t) => tags.has(t)))
    .map((q) => ({ ...q, cite: [...new Set(ev.filter((e) => q.tags.includes(e.tag)).map((e) => e.cite))].slice(0, 3) }))
    .sort((x, y) => +(y.level === "높음") - +(x.level === "높음"));
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
  return "ok";
}
