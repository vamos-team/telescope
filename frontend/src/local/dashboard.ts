// 자료 검색 대시보드 3개 (v01-stella 회사 재무 6탭에서 추림). 값은 dashboard.json (템플릿 /api/dashboard, DART 공시, 억원)
// 실제 서비스: DART XBRL → DuckDB 적재 → mcp-server 조회. 비율·합계는 코드로 계산
import D from "./dashboard.json";

export const DASH = D as { source: string; years: number[]; unit: string; companies: string[]; revenue: Record<string, number[]>;
  op_income: Record<string, number[]>; capex: Record<string, number[]>; dep_ppe: Record<string, number[]>;
  liabilities: Record<string, number[]>; equity: Record<string, number[]> };
const last = (xs: number[]) => xs[xs.length - 1];
export const opMargin = (co: string) => last(DASH.op_income[co]) / last(DASH.revenue[co]) * 100;
export const debtRatio = (co: string) => last(DASH.liabilities[co]) / last(DASH.equity[co]) * 100;

export const DASH_PANELS = [
  { id: "opi", title: "3사 영업이익 추이", sub: "연결, 2021–2025" },
  { id: "capex", title: "SKT CapEx와 유형자산 감가상각비", sub: "SKT+SKB 합산, 연결, 2021–2025" },
  { id: "peer", title: "3사 주요 지표", sub: "연결, 2025" },
] as const;
export const panelOf = (id: string) => DASH_PANELS.find((p) => p.id === id);

// 패널 → 근거 줄 (결과물·질문 생성 입력). 억원 → 십억원은 코드로 환산
export function dashEvidence(panel: string) {
  const y = String(last(DASH.years)), bil = (v: number) => +(v / 10).toFixed(1);
  const row = (tag: string, co: string, label: string, v: number) => ({ tag, company: co, label, value: bil(v), unit: "십억원", period: y, cite: `dash:${panel}#${co}` });
  const cos = DASH.companies;
  if (panel === "opi") return cos.map((co) => row("opi", co, "영업이익", last(DASH.op_income[co])));
  if (panel === "capex") return [row("capex", "SKT", "CapEx", last(DASH.capex.SKT)), row("da", "SKT", "유형자산 감가상각비", last(DASH.dep_ppe.SKT))];
  if (panel === "peer") return cos.flatMap((co) => [row("rev", co, "매출", last(DASH.revenue[co])), row("opi", co, "영업이익", last(DASH.op_income[co])),
    row("capex", co, "CapEx", last(DASH.capex[co]))]);
  return [];
}

// 컨텍스트용 표 텍스트 (억원 원본값)
export function dashText(panel: string) {
  const ys = DASH.years.join("\t"), line = (n: string, xs: number[]) => `${n}\t${xs.join("\t")}`;
  if (panel === "opi") return [`회사\t${ys}`, ...DASH.companies.map((co) => line(co, DASH.op_income[co]))].join("\n");
  if (panel === "capex") return [`항목\t${ys}`, line("CapEx", DASH.capex.SKT), line("유형자산 감가상각비", DASH.dep_ppe.SKT)].join("\n");
  return ["회사\t매출\t영업이익\t영업이익률\t부채비율\tCapEx", ...DASH.companies.map((co) =>
    `${co}\t${last(DASH.revenue[co])}\t${last(DASH.op_income[co])}\t${opMargin(co).toFixed(1)}%\t${debtRatio(co).toFixed(1)}%\t${last(DASH.capex[co])}`)].join("\n");
}
