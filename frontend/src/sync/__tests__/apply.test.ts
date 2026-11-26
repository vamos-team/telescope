import { describe, expect, it } from "vitest";
import { applyRemote, type CanvasState } from "../apply";
import { fromServerEvent, toServerEvent } from "../protocol";
import type { ChartCard, CompareCard, MemoCard, TableCard } from "../../stores/canvas";

const empty = (): CanvasState => ({ cards: [], links: [] });

describe("applyRemote", () => {
  it("서버에서 온 카드 추가·이동·카드 메모를 화면 상태에 반영한다", () => {
    const s = empty();
    applyRemote(s, fromServerEvent({ type: "add_card", cardId: "a", tableId: "SKT_is", rows: [5, 3], cols: [1, 0], x: 10, y: 20 }), "박망");
    applyRemote(s, fromServerEvent({ type: "move_card", cardId: "a", x: 300, y: 120 }), "박망");
    applyRemote(s, fromServerEvent({ type: "set_card_memo", cardId: "a", text: "추이 확인", baseVersion: 0 }), "박망");

    const card = s.cards[0] as TableCard;
    expect(card).toMatchObject({ id: "a", tableId: "SKT_is", rows: [3, 5], cols: [0, 1], vcol: 0, x: 300, y: 120, memo: "추이 확인" });
  });

  it("표 카드의 행·기간·정렬·수정값을 반영한다", () => {
    const s = empty();
    applyRemote(s, { type: "add_card", card_id: "a", table_id: "t", rows: [1], cols: [0, 1] }, "x");
    applyRemote(s, { type: "add_rows", card_id: "a", rows: [3, 1] }, "x");
    applyRemote(s, { type: "remove_row", card_id: "a", r: 1 }, "x");
    applyRemote(s, { type: "select_period", card_id: "a", c: 1 }, "x");
    applyRemote(s, { type: "sort_card", card_id: "a", key: "1Q26", desc: true }, "x");
    applyRemote(s, { type: "edit_cell", card_id: "a", r: 3, c: 0, value: 12.5 }, "x");

    expect(s.cards[0]).toMatchObject({ rows: [3], vcol: 1, sort: { key: "1Q26", desc: true }, edits: { "3,0": 12.5 } });
  });

  it("카드를 지우면 닿는 연결을 지우고, 시각화 카드는 남긴다", () => {
    const s = empty();
    applyRemote(s, { type: "add_card", card_id: "a", table_id: "t", rows: [], cols: [] }, "x");
    applyRemote(s, { type: "add_card", card_id: "b", table_id: "t", rows: [], cols: [] }, "x");
    applyRemote(s, { type: "create_compare", card_id: "cmp", sources: ["a", "b"] }, "x");
    applyRemote(s, { type: "create_compare", card_id: "ch", sources: ["a", "b"], view: "chart" }, "x");
    applyRemote(s, { type: "add_link", link_id: "l", source: "a", target: "b" }, "x");

    applyRemote(s, { type: "remove_card", card_id: "a" }, "x");
    applyRemote(s, { type: "remove_card", card_id: "cmp" }, "x");

    expect(s.links).toEqual([]);
    expect(s.cards.map((c) => c.id)).toEqual(["b", "ch"]);
    expect((s.cards[1] as ChartCard)).toMatchObject({ kind: "chart", sources: ["b"] });
  });

  it("비교 원본에서 지운 카드를 뺀다", () => {
    const s = empty();
    applyRemote(s, { type: "add_card", card_id: "a", table_id: "t", rows: [], cols: [] }, "x");
    applyRemote(s, { type: "add_card", card_id: "b", table_id: "t", rows: [], cols: [] }, "x");
    applyRemote(s, { type: "create_compare", card_id: "cmp", sources: ["a", "b"] }, "x");

    applyRemote(s, { type: "remove_card", card_id: "a" }, "x");

    expect((s.cards.find((c) => c.id === "cmp") as CompareCard).sources).toEqual(["b"]);
  });

  it("이미 없는 카드를 가리키는 변경은 무시한다", () => {
    const s = empty();
    expect(applyRemote(s, { type: "move_card", card_id: "gone", x: 1, y: 1 }, "x")).toBe(false);
    expect(applyRemote(s, { type: "add_link", link_id: "l", source: "gone", target: "nope" }, "x")).toBe(false);
  });

  it("메모 카드를 만들고 본문을 고친다", () => {
    const s = empty();
    applyRemote(s, { type: "add_memo", card_id: "p", text: "초안 메모" }, "박망");
    applyRemote(s, { type: "edit_memo", card_id: "p", text: "고친 메모" }, "박망");

    expect(s.cards[0] as MemoCard).toMatchObject({ kind: "memo", author: "박망", text: "고친 메모" });
  });

  it("선 메모와 작성자를 반영한다", () => {
    const s = empty();
    applyRemote(s, { type: "add_card", card_id: "a", table_id: "t", rows: [], cols: [] }, "x");
    applyRemote(s, { type: "add_memo", card_id: "m", text: "" }, "x");
    applyRemote(s, { type: "add_link", link_id: "l", source: "m", target: "a" }, "x");
    applyRemote(s, { type: "set_link_memo", link_id: "l", text: "근거로 쓸 것", baseVersion: 0 }, "박망");

    expect(s.links[0]).toMatchObject({ memo: "근거로 쓸 것", memoBy: "박망" });
  });
});

describe("protocol", () => {
  it("화면 형식과 서버 형식을 오가도 같은 이벤트가 된다(작성자는 서버로 보내지 않음)", () => {
    const local = { type: "confirm_mapping", concept_id: "da", company: "SKT", cells: ["t#r1c0"], accepted: true, author: "x" };
    const server = toServerEvent(local);

    expect(server).toEqual({ type: "confirm_mapping", conceptId: "da", company: "SKT", cells: ["t#r1c0"], accepted: true });
    expect(fromServerEvent(server)).toEqual({ type: "confirm_mapping", concept_id: "da", company: "SKT", cells: ["t#r1c0"], accepted: true });
  });
});
