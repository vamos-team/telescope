// 메모 입력 칸이 다른 사람이 바꾼 글을 받을 때의 규칙(새로고침 없이 반영, 내가 고치던 글은 지킴)
import { expect, it } from "vitest";
import { receiveText } from "../memoDraft";

it("보기 중이면 받은 글로 바꾼다", () => {
  expect(receiveText({ editing: false, draft: "예전 글" }, "새 글", "예전 글")).toEqual({ editing: false, draft: "새 글" });
});

it("다른 사람이 만든 빈 메모에 처음 쓴 글을 받으면 쓰는 중이어도 보기로 바꿔 보여 준다", () => {
  expect(receiveText({ editing: true, draft: "" }, "표 감소를 설명", "")).toEqual({ editing: false, draft: "표 감소를 설명" });
});

it("내가 고치던 중이면 받은 글로 덮어쓰지 않는다", () => {
  const typing = { editing: true, draft: "내가 쓰던 글" };
  expect(receiveText(typing, "다른 사람 글", "")).toBe(typing);
});

it("손대지 않은 채 쓰는 중인데 글이 지워지면 쓰는 중으로 남는다", () => {
  expect(receiveText({ editing: true, draft: "예전 글" }, "", "예전 글")).toEqual({ editing: true, draft: "" });
});
