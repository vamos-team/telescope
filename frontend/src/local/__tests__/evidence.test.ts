import { expect, it } from "vitest";
import { api } from "../../api";
import { assumptions, contextNote, review } from "../ai";
import { dashColumnIndices, dashEvidence, dashPeriod, panelOf, tableOf } from "../dashboard";
import { quarterIndex } from "../period";
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

it("기준 분기까지의 값만 정리 글과 근거에 사용하고 원래 열 인용을 유지한다", async () => {
  const sessionId = crypto.randomUUID();
  const cards = [{ id: "q", kind: "dash" as const, panel: "plq_op", x: 0, y: 0 }];
  await api.postEvents(sessionId, [{ type: "add_dash", card_id: "q", panel: "plq_op" }]);
  const cutoff = quarterIndex("2026년 1분기");
  expect(cutoff).toBe(quarterIndex("1Q26"));
  expect(cutoff).toBe(quarterIndex("26.1Q"));
  expect(cutoff).toBe(quarterIndex("2026Q1"));
  expect(cutoff).toBe(quarterIndex("2026-03-31"));
  expect(dashEvidence("plq_op")[0].period).toBe("26.2Q");
  const ev = await api.evidence(sessionId, cutoff);
  expect(ev).toHaveLength(1);
  expect(ev[0]).toMatchObject({ period: "26.1Q", cite: expect.stringMatching(/@8$/) });
  expect(assumptions(api.analysis, ev).some((a) => a.cite === ev[0].cite)).toBe(true);
  const note = await contextNote({ canvas: "예산", quarter: "2026년 1분기", sessionId, cards,
    links: [], memos: [], notes: [], title: (id) => id });
  expect(note).toContain(`(26.1Q) [${ev[0].cite}]`);
  expect(note).not.toContain("26.2Q");
  const context = (await api.context(sessionId, "", cutoff)).layers.L2_workspace;
  expect(context).toContain("26.1Q");
  expect(context).not.toContain("26.2Q");
  expect(await api.evidence(sessionId, quarterIndex("2023년 4분기"))).toEqual([]);
});

it("동종사 표는 다섯 지표를 근거로 내보내고 지수·누적액은 원계정과 구분한다", () => {
  const peer = dashEvidence("peer").filter((e) => e.company === "KT");
  expect(peer.map(({ tag, unit, period }) => [tag, unit, period])).toEqual([
    ["rev", "십억원", "2025"], ["opi", "십억원", "2025"], ["opm", "%", "2025"], ["debt_ratio", "%", "2025"], ["capex", "십억원", "2025"],
  ]);
  expect(peer[0].value).toBeCloseTo(28_244.2);
  expect(peer[2].value).toBeCloseTo(peer[1].value / peer[0].value * 100, 2);
  expect(dashEvidence("peer_rev_idx").map((e) => e.tag)).toEqual(["rev_index", "rev_index", "rev_index"]);
  expect(dashEvidence("cx_cum").map((e) => e.tag)).toEqual(["capex_cumulative", "capex_cumulative", "capex_cumulative"]);
  expect(dashEvidence("pl_rev")[0].tag).toBe("rev");
  expect(dashEvidence("peer_capex")[0].tag).toBe("capex");
});

it("2024년 정리 글은 연간 인사이트의 2025년 증감과 해석을 숨긴다", async () => {
  const quarter = "2024년 4분기", cutoff = quarterIndex(quarter), sid = crypto.randomUUID();
  const panels = ["ins_op", "ins_capex"];
  const cards = panels.map((panel) => ({ id: panel, panel, kind: "dash" as const, x: 0, y: 0 }));
  const [op, capex] = panels.map((id) => tableOf(panelOf(id)!));
  expect(op.cols.map((_, i) => dashPeriod(op, i))).toEqual(["2024", "2025", "2025"]);
  expect(capex.cols.map((_, i) => dashPeriod(capex, i))).toEqual(["2023", "2025", "2025"]);
  expect(dashColumnIndices(op, cutoff)).toEqual([0]);
  expect(dashColumnIndices(capex, cutoff)).toEqual([0]);
  await api.postEvents(sid, cards.map((c) => ({ type: "add_dash", card_id: c.id, panel: c.panel })));
  const context = (await api.context(sid, "", cutoff)).layers.L2_workspace;
  expect(context).toContain("항목\t2024\n");
  expect(context).toContain("항목\t2023\n");
  expect(context).not.toMatch(/2025|증감/);
  const note = await contextNote({ canvas: "예산", quarter, sessionId: sid, cards, links: [], memos: [], notes: [], title: (id) => id });
  expect(note).toMatch(/\(2024\).*\[dash:ins_op#/);
  expect(note).toMatch(/\(2023\).*\[dash:ins_capex#/);
  expect(note).not.toMatch(/2025|AI 해석|2년째/);
});

it("과거 실적 열도 해당 공시가 공개된 분기부터 근거에 포함한다", async () => {
  const sid = crypto.randomUUID(), tableId = "SKT_2026Q1:1:1";
  const cards = [{ id: "s", kind: "table" as const, tableId, rows: [5], cols: [0], vcol: 0,
    edits: {}, sort: null, checked: false, x: 0, y: 0 }];
  await api.postEvents(sid, [{ type: "add_card", card_id: "s", table_id: tableId, rows: [5], cols: [0] }]);
  const before = quarterIndex("2026년 1분기"), after = quarterIndex("2026년 2분기");
  expect(await api.evidence(sid, before)).toEqual([]);
  expect(await api.evidence(sid, after)).toContainEqual(expect.objectContaining({ period: "1Q25", source: "공시" }));
  expect(api.peerFacts(["da"], before).tables).toEqual([]);
  expect(api.peerFacts(["da"], after).tables).toContain(tableId);
  expect((await api.context(sid, "", before)).messages.every(({ content }) => !content.includes(tableId))).toBe(true);
  const note = await contextNote({ canvas: "예산", quarter: "2026년 1분기", sessionId: sid, cards,
    links: [], memos: [], notes: [], title: (id) => id });
  expect(note).not.toContain(tableId);
  expect(note).not.toContain("1Q25");
});

it("비교 상대 행을 뺀 뒤에도 거부 상태를 유지하고 되돌릴 수 있다", async () => {
  const sid = crypto.randomUUID();
  await api.postEvents(sid, [
    { type: "add_card", card_id: "s", table_id: "SKT_2026Q1:1:1", rows: [5], cols: [1] },
    { type: "add_card", card_id: "k", table_id: "KT_2026Q1:2:1", rows: [0, 1], cols: [0] },
    { type: "create_compare", card_id: "cmp", sources: ["s", "k"] },
    { type: "confirm_mapping", concept_id: "da", company: "SKT", cells: [], accepted: false },
    { type: "remove_row", card_id: "k", r: 1 },
  ]);
  const result = await api.compare(sid, "cmp");
  expect(result.lines).toContainEqual(expect.objectContaining({ label: "종업원급여", why: "표 그대로" }));
  expect(result.unmatched).toContainEqual(expect.objectContaining({ label: "감가상각비", concept_id: "da", why: "거부한 대응" }));
  await api.postEvents(sid, [{ type: "confirm_mapping", concept_id: "da", company: "SKT", cells: [], accepted: null }]);
  expect((await api.compare(sid, "cmp")).rejected).toEqual([]);
});

it("로컬에서 파일 헤더를 확인하고 비교 대상 언급으로 회사를 추정하지 않는다", () => {
  expect(checkUpload("SKT.pdf", null, new Uint8Array([1, 2])).code).toBe("FORMAT_MISMATCH");
  expect(checkUpload("SKT.pdf", null, new TextEncoder().encode("%PDF-")).company).toBe("SKT");
  expect(checkUpload("삼성전자 설비.txt", "SKT와 비교").code).toBe("COMPANY_MISMATCH");
  expect(checkUpload("설비.txt", "SKT와 비교").code).toBe("COMPANY_MISMATCH");
  expect(checkUpload("설비.txt", "대상 회사: KT\nSKT와 비교").company).toBe("KT");
});
