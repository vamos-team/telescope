import { describe, expect, it } from "vitest";
import { SeqTracker } from "../seq";
import type { EventsMessage } from "../protocol";

const msg = (firstSeq: number, lastSeq: number, requestId = `r${firstSeq}`): EventsMessage =>
  ({ firstSeq, lastSeq, requestId, author: { id: 2 }, events: [], textVersions: {} });

describe("SeqTracker", () => {
  it("번호가 이어지면 반영하고, 내 묶음이면 번호만 올린다", () => {
    const t = new SeqTracker();
    t.start(10);
    t.markMine("mine");

    expect(t.receive(msg(11, 12)).action).toBe("apply");
    expect(t.receive(msg(13, 13, "mine")).action).toBe("own");
    expect(t.lastSeq).toBe(13);
  });

  it("번호가 비면 다시 조회하라고 알린다", () => {
    const t = new SeqTracker();
    t.start(10);

    expect(t.receive(msg(12, 12)).action).toBe("gap");
  });

  it("이미 반영한 번호는 무시한다", () => {
    const t = new SeqTracker();
    t.start(10);

    expect(t.receive(msg(9, 10)).action).toBe("stale");
  });

  it("조회 전에 온 메시지는 모아 두었다가 조회한 번호 뒤의 것만 순서대로 반영한다", () => {
    const t = new SeqTracker();
    expect(t.receive(msg(12, 12)).action).toBe("buffered");
    expect(t.receive(msg(10, 11)).action).toBe("buffered");

    const decisions = t.start(11);

    expect(decisions.map((d) => d.action)).toEqual(["apply"]);
    expect(t.lastSeq).toBe(12);
  });
});
