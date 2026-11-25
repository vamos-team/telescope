// 규칙 기반 목업 AI. 실제 서비스에서는 context()의 프롬프트를 LLM에 보내고, 응답의 근거 ID를 서버가 검증한다.
// 여기서는 같은 입력(캔버스 근거 + data/demo/analysis.json 양식)으로 항상 같은 결과를 만든다.
import type { Analysis, Deliverable, Evidence } from "../api";

export interface Line { text: string; cite: string[]; kind: "공시" | "IR" | "AR" | "뉴스" | "사내" | "AI 해석" | "근거 부족" | "프롬프트"; hint?: string }
// 사용자 프롬프트: 프롬프트 카드(cardId 없음 또는 자기 ID), 카드 프롬프트(cardId), 선 프롬프트(where에 출발 → 도착)
export interface UserPrompt { cardId?: string; where: string; text: string }
export interface Draft { deliverable: Deliverable; sections: { title: string; lines: Line[] }[];
  assumptions: { tag: string; name: string; value: number; cite: string; period: string }[] }
export interface Question { topic: string; q: string; why: string; level: string; cite: string[] }

const CUR = /1Q26|당분기|제 10 기 1분기/;  // 기준 분기 열 (server.ts peerFacts와 같은 규칙)
const num = (v: number) => v.toLocaleString("ko-KR", { maximumFractionDigits: 1 });

export function draft(a: Analysis, ev: Evidence[], id: string, prompts: UserPrompt[] = []): Draft {
  const d = a.deliverables.find((x) => x.id === id)!;
  const name = (t: string) => a.tag_names[t] ?? t;
  // 카드 프롬프트는 그 카드에서 나온 근거 줄 옆에 지시로 표시 (목업: 실제 서비스는 LLM이 지시대로 문장을 씀)
  const hintOf = (cardId: string) => prompts.filter((p) => p.cardId === cardId).map((p) => p.text).join(" / ") || undefined;
  const sections = d.sections.map((s) => {
    const lines: Line[] = [];
    for (const tag of s.tags) {
      const es = ev.filter((e) => e.tag === tag);
      if (!es.length) { lines.push({ text: `${name(tag)}: 캔버스에 근거가 없음. 관련 공시·IR·사내 자료를 올려 보완 필요`, cite: [], kind: "근거 부족" }); continue; }
      // 주 대상 회사의 숫자 먼저, 비교 회사는 뒤에
      const nums = es.filter((e) => e.value != null).sort((x, y) => +(y.company === a.focus) - +(x.company === a.focus));
      for (const e of nums.slice(0, 3))
        lines.push({ text: `${e.company} ${e.label} ${num(e.value!)}${e.unit} (${e.period}${e.edited ? ", 사용자 수정값" : ""})`, cite: [e.cite], kind: e.source, hint: hintOf(e.card_id) });
      const doc = es.find((e) => e.text);
      if (doc) lines.push({ text: doc.text!, cite: [doc.cite], kind: doc.source, hint: hintOf(doc.card_id) });
    }
    const cited = lines.filter((l) => l.cite.length).length;
    if (cited) lines.push({ text: `${s.tags.map(name).join("·")} 근거 ${cited}건을 바탕으로 ${d.goal}의 이 항목을 구성함`, cite: [], kind: "AI 해석" });
    return { title: s.title, lines };
  });
  // 다음 분기 가정: 주 대상 회사의 직전 분기(기준 분기) 공시 원본값을 그대로 이어 간다고 두고, 평가 단계에서 실제 실적과 대조
  // 사용자 수정값과 다른 기간 열(예: 전년 동기)은 가정에 쓰지 않음
  const assumptions = d.assume.flatMap((tag) => {
    const e = ev.find((x) => x.tag === tag && x.company === a.focus && x.source === "공시" && x.value != null && !x.edited && CUR.test(x.period));
    return e ? [{ tag, name: name(tag), value: e.value!, cite: e.cite, period: e.period }] : [];
  });
  if (prompts.length) sections.unshift({ title: "작성 지시", lines: prompts.map((p) => ({ text: `${p.where}: ${p.text}`, cite: [], kind: "프롬프트" as const })) });
  if (assumptions.length) sections.push({ title: "부록. 다음 분기 가정", lines: assumptions.map((x) =>
    ({ text: `${a.next_quarter.label} ${x.name}는 직전 분기(${x.period}) 수준 ${num(x.value)}십억원 유지 가정`, cite: [x.cite], kind: "AI 해석" as const })) });
  return { deliverable: d, sections, assumptions };
}

export function questions(a: Analysis, ev: Evidence[]): Question[] {
  const tags = new Set(ev.map((e) => e.tag));
  return a.questions.filter((q) => q.tags.some((t) => tags.has(t)))
    .map((q) => ({ ...q, cite: [...new Set(ev.filter((e) => q.tags.includes(e.tag)).map((e) => e.cite))].slice(0, 3) }))
    .sort((x, y) => +(y.level === "높음") - +(x.level === "높음"));
}

// 다음 분기 평가: 질문은 리포트 쟁점과 같은 주제면 적중, 초안 가정은 실제 실적과 차이율로 판정
export function evaluate(a: Analysis, qs: Question[], d: Draft | null) {
  const nq = a.next_quarter, issueTopics = new Set(nq.issues.map((i) => i.topic)), qTopics = new Set(qs.map((q) => q.topic));
  const hit = qs.filter((q) => issueTopics.has(q.topic)), off = qs.filter((q) => !issueTopics.has(q.topic));
  const missed = nq.issues.filter((i) => !qTopics.has(i.topic));
  const pct = (x: number, n: number) => (n ? Math.round((x / n) * 100) : 0);
  const checks = (d?.assumptions ?? []).filter((x) => nq.actuals[x.tag] != null).map((x) => {
    const actual = nq.actuals[x.tag], diff = ((x.value - actual) / actual) * 100;
    return { ...x, actual, diff, ok: Math.abs(diff) <= 5 };
  });
  return { hit: hit.map((q) => ({ q, issue: nq.issues.find((i) => i.topic === q.topic)! })), off, missed, checks,
    precision: pct(hit.length, qs.length), recall: pct(nq.issues.length - missed.length, nq.issues.length) };
}

// ponytail: 판정 규칙 확인용 자체 점검. 브라우저 콘솔에서 window.__aiSelfCheck() 로 실행
export function selfCheck(a: Analysis) {
  const ev: Evidence[] = [{ tag: "da", company: a.focus, label: "감가상각비", value: 590.7, unit: "십억원", period: "1Q26", cite: "x#r5c1", card_id: "c1", source: "공시" }];
  const qs = questions(a, ev), d = draft(a, ev, "budget"), r = evaluate(a, qs, d);
  console.assert(qs.length === 1 && qs[0].topic === "da_trend", "질문: da 태그는 da_trend 하나");
  console.assert(r.precision === 0 && r.recall === 0, "평가: da_trend는 2Q 쟁점에 없음");
  console.assert(d.sections.some((s) => s.lines.some((l) => l.kind === "근거 부족")), "초안: 없는 태그는 근거 부족");
  console.assert(r.checks.length === 1 && !r.checks[0].ok === (Math.abs(r.checks[0].diff) > 5), "가정 대조");
  console.assert(draft(a, [{ ...ev[0], edited: true }], "budget").assumptions.length === 0, "가정: 사용자 수정값 제외");
  console.assert(draft(a, [{ ...ev[0], period: "1Q25" }], "budget").assumptions.length === 0, "가정: 기준 분기 열만");
  return "ok";
}
