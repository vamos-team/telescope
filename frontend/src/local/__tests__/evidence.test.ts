import { expect, it } from "vitest";
import { api } from "../../api";
import { review } from "../ai";
import { dashEvidence } from "../dashboard";
import { checkUpload } from "../validate";

it("KPI 근거 ID가 정리 글의 금액을 가리키고 산점도는 비율과 매출을 구분한다", async () => {
  const sessionId = crypto.randomUUID();
  const cards = [{ id: "k", kind: "dash" as const, panel: "kpis", x: 0, y: 0 }];
  await api.postEvents(sessionId, [
    { type: "add_dash", card_id: "dps", panel: "cf_dps" },
    { type: "add_dash", card_id: "scatter", panel: "peer_scatter" },
  ]);
  const context = (await api.context(sessionId, "")).layers.L2_workspace;
  expect(context).toContain("단위 원 | dash:cf_dps");
  expect(context).toContain("| dash:peer_scatter\n항목\t영업이익률(%)\t부채비율(%)\t매출(억원)");
  const scatter = dashEvidence("peer_scatter").filter((e) => e.company === "KT");
  expect(scatter.map(({ tag, unit, period }) => [tag, unit, period])).toEqual([
    ["opm", "%", "2025"], ["debt_ratio", "%", "2025"], ["rev", "십억원", "2025"],
  ]);
  expect(scatter[2].value).toBeCloseTo(28_244.2);
  expect(dashEvidence("capex_ratio")[0]).toMatchObject({ tag: "capex_ratio", unit: "배", value: 0.77 });
  expect(dashEvidence("capex").some(({ tag }) => tag === "capex")).toBe(true);
  const result = await review(api.analysis, {
    canvas: "예산", quarter: "2026년 1분기", cards: [...cards, { id: "n", kind: "doc", docId: "news-v01-9", x: 0, y: 0 }],
    sessionId, title: (id) => id, links: [], memos: [], notes: [], ev: [], tags: {},
  });
  const rev = dashEvidence("kpis").find((e) => e.tag === "rev")!;
  expect(result.note).toContain(`매출: 170,992억원 (2025) [${rev.cite}]`);
  expect(result.claims.find(({ cardId }) => cardId === "n")).toMatchObject({ from: expect.stringContaining("뉴스 제목") });
});

it("같은 회사의 두 표에서 반복되는 계정을 빠뜨리지 않는다", async () => {
  const sessionId = crypto.randomUUID();
  const sources = ["KT_2026Q1:2:1", "KT_2026Q1:2:2"];
  await api.postEvents(sessionId, sources.map((tableId) => ({
    type: "add_card", card_id: tableId, table_id: tableId, cols: [0],
    rows: [api.tableInfo(tableId).rows.findIndex((row) => row.label === "종업원급여")],
  })));
  await api.postEvents(sessionId, [{ type: "create_compare", card_id: "compare", sources }]);
  const result = await api.compare(sessionId, "compare");
  const parts = [...result.lines, ...result.unmatched].flatMap(({ values }) => values.flat());
  expect(parts.filter((part) => part?.label.includes("종업원급여")).map((part) => part?.card_id).sort()).toEqual(sources);
});

it("로컬에서 파일 헤더를 확인하고 비교 대상 언급으로 회사를 추정하지 않는다", () => {
  expect(checkUpload("SKT.pdf", null, new Uint8Array([1, 2])).code).toBe("FORMAT_MISMATCH");
  expect(checkUpload("SKT.pdf", null, new TextEncoder().encode("%PDF-")).company).toBe("SKT");
  expect(checkUpload("삼성전자 설비.txt", "SKT와 비교").code).toBe("COMPANY_MISMATCH");
  expect(checkUpload("설비.txt", "SKT와 비교").code).toBe("COMPANY_MISMATCH");
  expect(checkUpload("설비.txt", "대상 회사: KT\nSKT와 비교").company).toBe("KT");
});
