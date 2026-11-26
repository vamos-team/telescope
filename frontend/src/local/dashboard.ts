// 자료 검색 대시보드 · 회사 재무 · 인사이트 패널 레지스트리 (v01-stella DART 대시보드를 패널 단위로 옮김). 값은 dart.ts (DART 공시, 억원)
// 패널 하나 = 자료 검색 화면의 카드 하나 = 캔버스 대시보드 카드 하나. 비율·합계·증감은 모두 여기서 코드로 계산한다 (LLM에 맡기지 않음)
// 실제 서비스: GET /api/v1/dashboard 가 같은 모양(패널 id · 제목 · 기준 · 차트 데이터)을 돌려준다
import { YEARS as YR, PEERS, CONS, SEP, CAPEX, PEER, PEER_DEP, QTR, DIV, OPEX_2025, OP_BRIDGE, CASH_BRIDGE, AQ, AAC, ANNUAL_INSIGHTS, type Peer } from "./dart";

export type Fmt = (v: number) => string;
export interface Series { name: string; v: number[]; c: string; fade?: boolean; bold?: boolean }
export type Chart =
  | { type: "bars"; labels: string[]; series: Series[]; labelLast?: boolean; fmt?: Fmt; W?: number; bw?: number }
  | { type: "lines"; labels: string[]; series: Series[]; fmt?: Fmt; axfmt?: Fmt; min?: number; max?: number; ref?: number; refLabel?: string; W?: number; pr?: number }
  | { type: "stack"; labels: string[]; series: Series[]; norm?: boolean; W?: number }
  | { type: "waterfall"; steps: [string, number, boolean?][] }
  | { type: "scatter"; points: { n: string; x: number; y: number; r: number; c: string }[]; xr: [number, number]; yr: [number, number]; xl: string; yl: string }
  | { type: "hbars"; items: { n: string; v: number; c: string }[]; fmt: Fmt; max: number }
  | { type: "treemap"; items: [string, number][]; colors: string[] }
  | { type: "heat"; cols: string[]; rows: { n: string; v: number[]; pct?: boolean }[] }
  | { type: "table"; cols: string[]; rows: { n: string; v: (number | string)[]; b?: boolean }[]; hl?: string }
  | { type: "kpis"; items: { l: string; tag: string; v: string; d: string; s: number[]; raw: number; unit: string }[] }
  | { type: "asset" };
export interface Panel { id: string; title: string; sub: string; span: number; unit: string; chart: () => Chart; cap?: () => string;
  ai?: string; news?: readonly string[] }  // ai · news: 인사이트 패널 (AI 해석 목업 + 관련 뉴스)

// ---------- 계산 ----------
export const com = (v: number) => (v < 0 ? "−" : "") + Math.round(Math.abs(v)).toLocaleString("ko-KR");
export const pct = (a: number, b: number) => (a / b - 1) * 100;
export const sgn = (v: number, d = 1, u = "%") => (v >= 0 ? "+" : "−") + Math.abs(v).toFixed(d) + u;
const last = <T>(xs: readonly T[]) => xs[xs.length - 1];
const div = (a: number[], b: number[], k = 100) => a.map((x, i) => (x / b[i]) * k);
const CAPEX_SUM = CAPEX.skt.map((v, i) => v + CAPEX.skb[i]);
const RATIO = CAPEX_SUM.map((v, i) => v / CONS.ppeDep[i]);
const OPM = div(CONS.op, CONS.rev), DR = div(CONS.L, CONS.E), FCF = CONS.cfo.map((v, i) => v + CONS.cfi[i]);
const OTHER_OPEX = CONS.opex.map((t, i) => t - CONS.fee[i] - CONS.dep[i] - CONS.pay[i]);
const cum = (a: number[]) => a.reduce<number[]>((o, v, i) => (o.push(v + (i ? o[i - 1] : 0)), o), []);
// 3사 (각 사 자회사 포함). SKT CapEx는 SKT+SKB 합산
export const P3: Record<Peer, { rev: number[]; op: number[]; L: number[]; E: number[]; cx: number[]; c: string }> = {
  SKT: { rev: CONS.rev, op: CONS.op, L: CONS.L, E: CONS.E, cx: CAPEX_SUM, c: "--s1" },
  KT: { ...PEER.KT, cx: CAPEX.kt, c: "--s2" }, "LGU+": { ...PEER["LGU+"], cx: CAPEX.lgu, c: "--s3" } };
const peerSeries = (f: (k: Peer) => number[]) => PEERS.map((k) => ({ name: k, v: f(k), c: P3[k].c, bold: k === "SKT" }));
const f1 = (v: number) => v.toFixed(1), fp = (v: number) => v.toFixed(1) + "%", f2 = (v: number) => v.toFixed(2);

// ---------- 패널 ----------
const PANELS: Panel[] = [
  // 대시보드
  { id: "kpis", title: "주요 지표 (2025)", sub: "SKB 포함 · CapEx는 SKT+SKB 합산", span: 12, unit: "억원", chart: () => {
    const k = (l: string, s: number[], unit = "억원", p = false) => ({ l, tag: "SKB 포함", s, raw: last(s), unit,
      v: p ? fp(last(s)) : com(last(s)), d: "2025 · 전년 대비 " + (p ? sgn(last(s) - s[3], 1, "%p") : sgn(pct(last(s), s[3]))) });
    return { type: "kpis", items: [k("매출", CONS.rev), k("영업이익", CONS.op), k("영업이익률", OPM, "%", true), k("당기순이익", CONS.ni),
      k("부채비율", DR, "%", true), { ...k("CapEx", CAPEX_SUM), tag: "SKT+SKB 합산" }] }; } },
  { id: "op_bridge", title: "영업이익 증감 요인 (2024–2025)", sub: "SKB 포함", span: 7, unit: "억원", chart: () => ({ type: "waterfall", steps: OP_BRIDGE }),
    cap: () => `영업이익 증감 ${com(CONS.op[4] - CONS.op[3])}억원` },
  { id: "peer_scatter", title: "영업이익률·부채비율 3사 비교 (2025)", sub: "각 사 자회사 포함", span: 5, unit: "%", cap: () => "원 크기: 매출",
    chart: () => ({ type: "scatter", xr: [4, 10], yr: [110, 140], xl: "영업이익률", yl: "부채비율",
      points: PEERS.map((k) => ({ n: k, x: (last(P3[k].op) / last(P3[k].rev)) * 100, y: (last(P3[k].L) / last(P3[k].E)) * 100, r: last(P3[k].rev), c: P3[k].c })) }) },
  { id: "cash_bridge", title: "현금 증감 내역 (2025)", sub: "SKB 포함", span: 7, unit: "억원", chart: () => ({ type: "waterfall", steps: CASH_BRIDGE }) },
  { id: "capex_ratio", title: "CapEx/감가상각비 비율 (2021–2025)", sub: "SKT+SKB 합산", span: 5, unit: "배", cap: () => "기준선 1.0: CapEx = 유형자산 감가상각비",
    chart: () => ({ type: "lines", labels: YR, series: [{ name: "CapEx/감가상각비", v: RATIO, c: "--s1", bold: true }], fmt: f2, min: 0.6, max: 1.2, ref: 1, refLabel: "1.0", W: 420, pr: 44 }) },
  { id: "yoy_heat", title: "주요 지표 전년 대비 증감 (2021–2025)", sub: "SKB 포함", span: 12, unit: "억원", cap: () => "셀 농도: 전년 대비 변화폭",
    chart: () => ({ type: "heat", cols: YR, rows: [{ n: "매출", v: CONS.rev }, { n: "영업이익", v: CONS.op }, { n: "영업이익률", v: OPM, pct: true }, { n: "당기순이익", v: CONS.ni },
      { n: "영업활동 현금흐름", v: CONS.cfo }, { n: "부채비율", v: DR, pct: true }, { n: "자산총계", v: CONS.A }, { n: "현금및현금성자산", v: CONS.cash }] }) },
  { id: "asset", title: "유형·무형자산 계정별 (분기)", sub: "SKT 별도", span: 12, unit: "억원", chart: () => ({ type: "asset" }) },

  // 회사 재무 › 손익 (연간 · 분기)
  ...(["y", "q"] as const).flatMap((p): Panel[] => {
    const q = p === "q", labs = q ? QTR.lab : YR, rev = q ? QTR.cons.rev : CONS.rev, op = q ? QTR.cons.op : CONS.op, per = q ? "(24.1Q–26.2Q)" : "(2021–2025)", pre = q ? "plq_" : "pl_";
    return [
      { id: pre + "rev", title: "매출 " + per, sub: "SKB 포함", span: 4, unit: "억원", chart: () => ({ type: "bars", labels: labs, series: [{ name: "매출", v: rev, c: "--s1", fade: true }], labelLast: true, W: 420 }) },
      { id: pre + "op", title: "영업이익 " + per, sub: "SKB 포함", span: 4, unit: "억원", chart: () => ({ type: "bars", labels: labs, series: [{ name: "영업이익", v: op, c: "--s1", fade: true }], labelLast: true, W: 420 }) },
      { id: pre + "opm", title: "영업이익률 " + per, sub: "SKB 포함", span: 4, unit: "%", chart: () => ({ type: "lines", labels: labs, series: [{ name: "", v: div(op, rev), c: "--s1", bold: true }], fmt: fp, axfmt: (a) => a + "%", W: 420, pr: 50 }) },
    ];
  }),
  { id: "plq_sep", title: "SKT 단독 분기 매출·영업이익 (24.1Q–26.2Q)", sub: "SKT 단독", span: 12, unit: "억원",
    chart: () => ({ type: "bars", labels: QTR.lab, series: [{ name: "매출", v: QTR.sep.rev, c: "--s1" }, { name: "영업이익", v: QTR.sep.op, c: "--s2" }], W: 900, bw: 22 }) },
  { id: "pl_opex_mix", title: "영업비용 구성비 (2021–2025)", sub: "SKB 포함", span: 7, unit: "억원",
    chart: () => ({ type: "stack", norm: true, labels: YR, W: 720, series: [{ name: "지급수수료", v: CONS.fee, c: "--s1" }, { name: "감가상각비", v: CONS.dep, c: "--s2" },
      { name: "종업원급여", v: CONS.pay, c: "--s3" }, { name: "그 밖의 비용", v: OTHER_OPEX, c: "--s4" }] }) },
  { id: "pl_opex_tree", title: "영업비용 구성 (2025)", sub: "SKB 포함", span: 5, unit: "억원", chart: () => ({ type: "treemap", items: OPEX_2025, colors: ["--s1", "--s2", "--s3", "--s4"] }) },
  { id: "pl_table", title: "손익계산서 (2021–2025)", sub: "SKB 포함", span: 12, unit: "억원", chart: () => ({ type: "table", cols: YR, hl: "억원", rows: [
    { n: "매출", v: CONS.rev, b: true }, { n: "이동전화수익", v: CONS.mob }, { n: "영업비용", v: CONS.opex, b: true }, { n: "감가상각비", v: CONS.dep }, { n: "종업원급여", v: CONS.pay },
    { n: "지급수수료", v: CONS.fee }, { n: "영업이익", v: CONS.op, b: true }, { n: "금융수익", v: CONS.finI }, { n: "금융비용", v: CONS.finC }, { n: "법인세비용", v: CONS.tax },
    { n: "당기순이익", v: CONS.ni, b: true }] }) },

  // 회사 재무 › 재무상태
  { id: "bs_le", title: "부채·자본 (2021–2025)", sub: "SKB 포함", span: 8, unit: "억원",
    chart: () => ({ type: "stack", labels: YR, W: 720, series: [{ name: "부채", v: CONS.L, c: "--s2" }, { name: "자본", v: CONS.E, c: "--s1" }] }) },
  { id: "bs_dr", title: "부채비율 (2021–2025)", sub: "SKB 포함", span: 4, unit: "%",
    chart: () => ({ type: "lines", labels: YR, series: [{ name: "", v: DR, c: "--s1", bold: true }], fmt: fp, axfmt: (a) => a + "%", W: 420, pr: 56 }) },
  { id: "bs_sep_dr", title: "SKT 단독 부채비율 (2021–2025)", sub: "SKT 단독", span: 4, unit: "%",
    chart: () => ({ type: "lines", labels: YR, series: [{ name: "", v: div(SEP.L, SEP.E), c: "--s1", bold: true }], fmt: fp, axfmt: (a) => a + "%", W: 420, pr: 56 }) },
  { id: "bs_cur", title: "유동자산·유동부채 (2021–2025)", sub: "SKB 포함", span: 6, unit: "억원",
    chart: () => ({ type: "bars", labels: YR, series: [{ name: "유동자산", v: CONS.curA, c: "--s1" }, { name: "유동부채", v: CONS.curL, c: "--s2" }], bw: 24 }) },
  { id: "bs_assets", title: "유형자산·무형자산·현금 (2021–2025)", sub: "SKB 포함", span: 6, unit: "억원",
    chart: () => ({ type: "lines", labels: YR, min: 0, series: [{ name: "유형", v: CONS.ppe, c: "--s1", bold: true }, { name: "무형", v: CONS.intg, c: "--s2" }, { name: "현금", v: CONS.cash, c: "--s3" }] }) },
  { id: "bs_table", title: "재무상태표 (2021–2025)", sub: "SKB 포함", span: 12, unit: "억원", chart: () => ({ type: "table", cols: YR, hl: "억원", rows: [
    { n: "자산총계", v: CONS.A, b: true }, { n: "유동자산", v: CONS.curA }, { n: "현금및현금성자산", v: CONS.cash }, { n: "비유동자산", v: CONS.A.map((a, i) => a - CONS.curA[i]) },
    { n: "유형자산", v: CONS.ppe }, { n: "무형자산", v: CONS.intg }, { n: "부채총계", v: CONS.L, b: true }, { n: "유동부채", v: CONS.curL },
    { n: "비유동부채", v: CONS.L.map((l, i) => l - CONS.curL[i]) }, { n: "자본총계", v: CONS.E, b: true }, { n: "부채비율", v: DR.map(fp) }] }) },

  // 회사 재무 › 현금흐름
  { id: "cf_act", title: "활동별 현금흐름 (2021–2025)", sub: "SKB 포함", span: 8, unit: "억원",
    chart: () => ({ type: "bars", labels: YR, W: 720, bw: 24, series: [{ name: "영업", v: CONS.cfo, c: "--s1" }, { name: "투자", v: CONS.cfi, c: "--s2" }, { name: "재무", v: CONS.cff, c: "--s3" }] }) },
  { id: "cf_fcf", title: "잉여현금흐름 (2021–2025)", sub: "SKB 포함", span: 4, unit: "억원", cap: () => "잉여현금흐름 = 영업활동 + 투자활동",
    chart: () => ({ type: "bars", labels: YR, W: 420, labelLast: true, series: [{ name: "잉여현금흐름", v: FCF, c: "--s1", fade: true }] }) },
  { id: "cf_cash", title: "현금및현금성자산 (2021–2025)", sub: "SKB 포함", span: 6, unit: "억원",
    chart: () => ({ type: "lines", labels: YR, min: 0, pr: 60, series: [{ name: "", v: CONS.cash, c: "--s1", bold: true }] }) },
  { id: "cf_dps", title: "주당 배당금 (2021–2025)", sub: "SKT 단독", span: 6, unit: "원",
    cap: () => "배당성향 " + DIV.payout.map((p, i) => YR[i].slice(2) + "년 " + p + "%").join(", ") + " · dart.duckdb 배당 데이터",
    chart: () => ({ type: "bars", labels: YR, labelLast: true, fmt: (v) => com(v) + "원", series: [{ name: "주당 배당금", v: DIV.dps, c: "--s1", fade: true }] }) },
  { id: "cf_table", title: "현금흐름표 (2021–2025)", sub: "SKB 포함", span: 12, unit: "억원", chart: () => ({ type: "table", cols: YR, hl: "억원", rows: [
    { n: "영업활동 현금흐름", v: CONS.cfo, b: true }, { n: "투자활동 현금흐름", v: CONS.cfi }, { n: "재무활동 현금흐름", v: CONS.cff }, { n: "잉여현금흐름", v: FCF, b: true }, { n: "기말 현금", v: CONS.cash }] }) },

  // 회사 재무 › 설비투자 (capex: 예전 대시보드 패널 id 유지)
  { id: "cx_stack", title: "CapEx (SKT·SKB, 2021–2025)", sub: "SKT+SKB 합산", span: 6, unit: "억원",
    chart: () => ({ type: "stack", labels: YR, W: 560, series: [{ name: "SKT 단독", v: CAPEX.skt, c: "--s1" }, { name: "SKB", v: CAPEX.skb, c: "--s2" }] }) },
  { id: "capex", title: "CapEx·유형자산 감가상각비 (2021–2025)", sub: "SKT+SKB 합산", span: 6, unit: "억원",
    chart: () => ({ type: "bars", labels: YR, bw: 24, series: [{ name: "CapEx", v: CAPEX_SUM, c: "--s1" }, { name: "유형자산 감가상각비", v: CONS.ppeDep, c: "--s2" }] }) },
  { id: "cx_cum", title: "누적 CapEx 3사 비교 (2021–2025)", sub: "SKT는 SKT+SKB 합산", span: 6, unit: "억원",
    chart: () => ({ type: "lines", labels: YR, series: peerSeries((k) => cum(P3[k].cx)) }) },
  { id: "cx_dep3", title: "유형자산 감가상각비 3사 비교 (2021–2025)", sub: "각 사 자회사 포함", span: 6, unit: "억원",
    chart: () => ({ type: "lines", labels: YR, series: peerSeries((k) => PEER_DEP[k]) }) },
  { id: "cx_table", title: "CapEx (2021–2025)", sub: "", span: 12, unit: "억원", chart: () => ({ type: "table", cols: YR, hl: "억원", rows: [
    { n: "SKT 단독", v: CAPEX.skt }, { n: "SKB", v: CAPEX.skb }, { n: "SKT+SKB 합산", v: CAPEX_SUM, b: true }, { n: "KT", v: CAPEX.kt }, { n: "LGU+", v: CAPEX.lgu },
    { n: "유형자산 감가상각비 (SKB 포함)", v: CONS.ppeDep }, { n: "CapEx/감가상각비", v: RATIO.map(f2), b: true }] }) },

  // 회사 재무 › 동종사 비교 (opi · peer: 예전 대시보드 패널 id 유지)
  { id: "opi", title: "3사 영업이익 추이 (2021–2025)", sub: "각 사 자회사 포함", span: 6, unit: "억원", chart: () => ({ type: "lines", labels: YR, series: peerSeries((k) => P3[k].op) }) },
  { id: "peer_rev_idx", title: "매출 지수 3사 비교 (2021=100)", sub: "각 사 자회사 포함", span: 6, unit: "지수",
    chart: () => ({ type: "lines", labels: YR, fmt: f1, ref: 100, refLabel: "100", axfmt: (a) => String(a), series: peerSeries((k) => P3[k].rev.map((v) => (v / P3[k].rev[0]) * 100)) }) },
  { id: "peer_opm", title: "영업이익률 3사 비교 (2025)", sub: "각 사 자회사 포함", span: 6, unit: "%",
    chart: () => ({ type: "hbars", fmt: fp, max: 10, items: PEERS.map((k) => ({ n: k, v: (last(P3[k].op) / last(P3[k].rev)) * 100, c: P3[k].c })).sort((a, b) => b.v - a.v) }) },
  { id: "peer_dr", title: "부채비율 3사 비교 (2021–2025)", sub: "각 사 자회사 포함", span: 6, unit: "%",
    chart: () => ({ type: "lines", labels: YR, fmt: fp, axfmt: (a) => a + "%", series: peerSeries((k) => div(P3[k].L, P3[k].E)) }) },
  { id: "peer_capex", title: "CapEx 3사 비교 (2021–2025)", sub: "SKT는 SKT+SKB 합산", span: 6, unit: "억원", chart: () => ({ type: "lines", labels: YR, series: peerSeries((k) => P3[k].cx) }) },
  { id: "peer", title: "주요 지표 3사 비교 (2025)", sub: "각 사 자회사 포함", span: 12, unit: "억원", chart: () => ({ type: "table", cols: ["매출", "영업이익", "영업이익률", "부채비율", "CapEx"], hl: "",
    rows: PEERS.map((k) => { const p = P3[k]; return { n: k, b: k === "SKT", v: [last(p.rev), last(p.op), fp((last(p.op) / last(p.rev)) * 100), fp((last(p.L) / last(p.E)) * 100), last(p.cx)] }; }) }) },

  // 인사이트 (연간, 사실 표 + AI 해석)
  ...ANNUAL_INSIGHTS.map((x): Panel => ({ id: x.id, title: x.t, sub: x.ft, span: 4, unit: x.id === "ins_div" ? "원" : "억원", ai: x.ai, news: x.news, chart: () => {
    const row = (n: string, a: number, b: number, u = "") => ({ n, b: n.startsWith("SKT"), v: [com(a) + u, com(b) + u, sgn(pct(b, a))] });
    if (x.id === "ins_op") return { type: "table", cols: ["2024", "2025", "증감"], hl: "", rows: PEERS.map((k) => row(k, P3[k].op[3], P3[k].op[4])) };
    if (x.id === "ins_capex") return { type: "table", cols: ["2023", "2025", "증감"], hl: "", rows: PEERS.map((k) => row(k === "SKT" ? "SKT+SKB" : k, P3[k].cx[2], P3[k].cx[4])) };
    return { type: "table", cols: ["2024", "2025", "증감"], hl: "", rows: [row("주당 배당금", DIV.dps[3], DIV.dps[4], "원"),
      { n: "배당성향", v: [DIV.payout[3] + "%", DIV.payout[4] + "%", sgn(DIV.payout[4] - DIV.payout[3], 1, "%p")] }] };
  } })),
];
export const panelOf = (id: string) => PANELS.find((p) => p.id === id);
// 자료 검색 화면 배치: 탭(대시보드 · 회사 재무 하위 탭 · 인사이트) → 패널 id
export const LAYOUT = {
  dash: ["kpis", "op_bridge", "peer_scatter", "cash_bridge", "capex_ratio", "yoy_heat", "asset"],
  fin: {
    pl: { label: "손익", y: ["pl_rev", "pl_op", "pl_opm", "pl_opex_mix", "pl_opex_tree", "pl_table"], q: ["plq_rev", "plq_op", "plq_opm", "plq_sep"] },
    bs: { label: "재무상태", y: ["bs_le", "bs_dr", "bs_cur", "bs_assets", "bs_sep_dr", "bs_table"] },
    cf: { label: "현금흐름", y: ["cf_act", "cf_fcf", "cf_cash", "cf_dps", "cf_table"] },
    capex: { label: "설비투자", y: ["cx_stack", "capex", "capex_ratio", "cx_cum", "cx_dep3", "cx_table"] },
    peer: { label: "동종사 비교", y: ["opi", "peer_rev_idx", "peer_opm", "peer_dr", "peer_capex", "peer"] },
  } as Record<string, { label: string; y: string[]; q?: string[] }>,
  insights: ANNUAL_INSIGHTS.map((x) => x.id) as string[],
};

// ---------- 패널 → 표 (컨텍스트 글 · 컨텍스트 층 · 근거 줄이 함께 씀) ----------
export interface PanelTable { cols: string[]; rows: { n: string; v: (number | string)[] }[]; unit: string }
export function tableOf(p: Panel): PanelTable {
  const c = p.chart(), u = p.unit;
  switch (c.type) {
    case "bars": case "lines": case "stack": return { cols: c.labels, rows: c.series.map((s) => ({ n: s.name || p.title.replace(/ \(.*$/, ""), v: s.v.map((x) => +x.toFixed(2)) })), unit: u };
    case "waterfall": return { cols: ["값"], rows: c.steps.map(([n, v]) => ({ n, v: [v] })), unit: u };
    case "scatter": return { cols: [c.xl + "(%)", c.yl + "(%)", "매출(억원)"], rows: c.points.map((x) => ({ n: x.n, v: [+x.x.toFixed(1), +x.y.toFixed(1), x.r] })), unit: "" };
    case "hbars": return { cols: ["2025"], rows: c.items.map((x) => ({ n: x.n, v: [+x.v.toFixed(1)] })), unit: u };
    case "treemap": return { cols: ["2025"], rows: c.items.map(([n, v]) => ({ n, v: [v] })), unit: u };
    case "heat": return { cols: c.cols, rows: c.rows.map((r) => ({ n: r.n, v: r.v.map((x) => (r.pct ? fp(x) : x)) })), unit: u };
    case "table": return { cols: c.cols, rows: c.rows, unit: u };
    case "kpis": return { cols: ["2025", "전년 대비"], rows: c.items.map((k) => ({ n: k.l, v: [k.unit === "%" ? fp(k.raw) : k.raw, k.d.replace(/^2025 · 전년 대비 /, "")] })), unit: u };
    case "asset": return { cols: AQ, rows: AAC.map(([, , n, v]) => ({ n, v })), unit: u };
  }
}
export function dashText(id: string) {
  const p = panelOf(id); if (!p) return "";
  const t = tableOf(p);
  return [`항목\t${t.cols.join("\t")}`, ...t.rows.map((r) => `${r.n}\t${r.v.map((x) => (typeof x === "number" ? String(x) : x)).join("\t")}`)].join("\n");
}
// 패널 → 근거 줄: 행마다 마지막 숫자 열. 억원 → 십억원은 코드로 환산. 개념 태그는 행·패널 이름으로 붙임 (질문 생성 입력)
const TAG: [RegExp, string][] = [[/CapEx/i, "capex"], [/감가상각/, "da"], [/배당/, "dividend"], [/순이익/, "ni"], [/영업이익/, "opi"], [/영업비용|비용 구성/, "opex"],
  [/수수료/, "comm"], [/종업원급여|인건비/, "labor"], [/부채/, "debt"], [/매출|수익/, "rev"]];
const tagOf = (s: string) => TAG.find(([re]) => re.test(s))?.[1];
const CO = (n: string) => (/^SKT/.test(n) ? "SKT" : /^KT/.test(n) ? "KT" : /^LG/.test(n) ? "LGU+" : null);
export function dashEvidence(id: string) {
  const p = panelOf(id); if (!p) return [];
  const t = tableOf(p), bil = p.unit === "억원" || t.unit === "억원";
  return t.rows.flatMap((r) => {
    const i = r.v.map((x) => typeof x === "number").lastIndexOf(true); if (i < 0) return [];
    const tag = tagOf(r.n) ?? tagOf(p.title); if (!tag) return [];
    const co = CO(r.n), raw = r.v[i] as number;
    return [{ tag, company: co ?? "SKT", label: co ? p.title.replace(/ \(.*$/, "") : r.n, value: bil ? +(raw / 10).toFixed(1) : raw, unit: bil ? "십억원" : p.unit,
      period: t.cols[i] ?? "2025", cite: `dash:${id}#${encodeURIComponent(r.n)}` }];
  });
}
