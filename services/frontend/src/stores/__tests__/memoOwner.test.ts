import { beforeEach, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useCanvas, type MemoCard } from "../canvas";

vi.mock("../../sync/config", () => ({ SYNC_MODE: "server", API_BASE: "", accessToken: () => "jwt", setAccessToken: () => {} }));

beforeEach(() => {
  vi.stubGlobal("window", { setTimeout: () => 0 });  // 조작 묶음 전송 타이머는 이 테스트와 무관
  setActivePinia(createPinia());
});

// 소유자라도 남의 메모(메모 카드 · 선 메모 · 카드 메모)는 고치거나 지우지 못한다. 전체 지우기는 예외
it("메모는 단 사람만 고치고 지운다", () => {
  const cv = useCanvas();
  Object.assign(cv, { canvasId: "5", serverRole: "owner", author: "이재무", user: { id: 1, employeeNo: "1001", name: "이재무", dept: "" },
    cards: [
      { id: "mine", kind: "memo", author: "이재무", authorId: 1, text: "내 메모", x: 0, y: 0 },
      { id: "other", kind: "memo", author: "박망", authorId: 2, text: "남의 메모", x: 0, y: 0 },
      { id: "t", kind: "doc", docId: "d", x: 0, y: 0, memo: "남의 카드 메모", memoBy: "박망", memoById: 2 },
    ],
    links: [{ id: "l", source: "mine", target: "t", memo: "남의 선 메모", memoBy: "박망", memoById: 2 }] });
  const memo = (id: string) => cv.cards.find((c) => c.id === id) as MemoCard | undefined;

  cv.editMemo("mine", "고친 내 메모");
  cv.editMemo("other", "고침");
  cv.setLinkMemo("l", "고침");
  cv.setCardMemo("t", "고침");
  cv.removeLink("l");
  cv.remove("other");
  cv.remove("t");     // 남의 카드 메모가 함께 사라지므로 막힘
  cv.remove("mine");  // 내 메모 카드지만 닿는 선에 남의 선 메모가 있어 막힘

  expect(memo("mine")?.text).toBe("고친 내 메모");
  expect(cv.cards.map((c) => c.id)).toEqual(["mine", "other", "t"]);
  expect(memo("other")?.text).toBe("남의 메모");
  expect(cv.links[0].memo).toBe("남의 선 메모");
  expect(cv.cards.find((c) => c.id === "t")?.memo).toBe("남의 카드 메모");
  expect(cv.error).toContain("다른 사람이 단 메모");

  cv.clear();
  expect(cv.cards).toEqual([]);
});
