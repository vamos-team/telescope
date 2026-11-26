import { describe, expect, it } from "vitest";
import { Outbox, SyncError, type Batch } from "../outbox";
import type { EventsResponse } from "../protocol";

const ok = (textVersions = {}): EventsResponse => ({ lastSeq: 1, firstChangedLayer: null, textVersions });
const ids = () => { let n = 0; return () => `req-${++n}`; };

describe("Outbox", () => {
  it("묶음마다 requestId를 붙이고 필드를 서버 형식(camelCase)으로 바꾼다", async () => {
    const box = new Outbox(ids(), () => ({ x: 10, y: 20 }));
    box.push({ type: "add_doc", card_id: "c-1", doc_id: "news-1", author: "이재무" });
    const sent: Batch[] = [];

    await box.flush(async (b) => { sent.push(b); return ok(); });

    expect(sent[0].requestId).toBe("req-1");
    expect(sent[0].events).toEqual([{ type: "add_doc", cardId: "c-1", docId: "news-1", x: 10, y: 20 }]);
  });

  it("전송이 실패하면 같은 requestId와 같은 내용으로 다시 보낸다", async () => {
    const box = new Outbox(ids());
    box.push({ type: "move_card", card_id: "c-1", x: 1, y: 1 });
    const sent: Batch[] = [];

    const first = await box.flush(async (b) => { sent.push(b); throw new SyncError(0, null, null, "network"); });
    box.push({ type: "move_card", card_id: "c-2", x: 2, y: 2 });  // 실패 뒤 새로 쌓인 조작은 다음 묶음으로
    await box.flush(async (b) => { sent.push(b); return ok(); });
    await box.flush(async (b) => { sent.push(b); return ok(); });

    expect(first.status).toBe("retry");
    expect(sent[1]).toEqual(sent[0]);
    expect(sent[2].requestId).toBe("req-2");
    expect(sent[2].events[0]).toMatchObject({ cardId: "c-2" });
  });

  it("같은 묶음 안의 같은 카드 이동과 같은 글 수정은 마지막 것만 보낸다", async () => {
    const box = new Outbox(ids());
    box.push({ type: "move_card", card_id: "c-1", x: 1, y: 1 });
    box.push({ type: "set_card_memo", card_id: "c-1", text: "감" });
    box.push({ type: "move_card", card_id: "c-1", x: 2, y: 2 });
    box.push({ type: "set_card_memo", card_id: "c-1", text: "감가상각" });
    let sent: Batch | undefined;

    await box.flush(async (b) => { sent = b; return ok(); });

    expect(sent!.events).toEqual([
      { type: "move_card", cardId: "c-1", x: 2, y: 2 },
      { type: "set_card_memo", cardId: "c-1", text: "감가상각", baseVersion: 0 },
    ]);
  });

  it("글 수정에는 알고 있는 버전을 baseVersion으로 붙이고, 응답의 새 버전을 기억한다", async () => {
    const box = new Outbox(ids());
    box.updateVersions({ "c-1": { memo: 3 } });
    box.push({ type: "set_card_memo", card_id: "c-1", text: "a" });
    let sent: Batch | undefined;

    await box.flush(async (b) => { sent = b; return ok({ "c-1": { memo: 4 } }); });
    box.push({ type: "set_card_memo", card_id: "c-1", text: "b" });
    await box.flush(async (b) => { sent = b; return ok(); });

    expect(sent!.events[0]).toMatchObject({ baseVersion: 4 });
  });

  it("409 K003이면 충돌한 글 수정만 빼고 나머지는 새 묶음으로 다시 보낸다", async () => {
    const box = new Outbox(ids());
    box.push({ type: "move_card", card_id: "c-1", x: 5, y: 5 });
    box.push({ type: "set_card_memo", card_id: "c-1", text: "늦게 고친 글" });
    const conflict = { targetId: "c-1", field: "memo", text: "먼저 고친 글", version: 1 };

    const result = await box.flush(async () => { throw new SyncError(409, "K003", conflict, "conflict"); });
    let resent: Batch | undefined;
    await box.flush(async (b) => { resent = b; return ok(); });

    expect(result).toEqual({ status: "conflict", conflict });
    expect(resent!.requestId).toBe("req-2");
    expect(resent!.events).toEqual([{ type: "move_card", cardId: "c-1", x: 5, y: 5 }]);
    expect(box.versions.get("c-1")?.memo).toBe(1);
  });

  it("권한·형식 오류(4xx)는 다시 보내지 않고 버린다", async () => {
    const box = new Outbox(ids());
    box.push({ type: "move_card", card_id: "c-1", x: 1, y: 1 });

    const result = await box.flush(async () => { throw new SyncError(403, "K002", null, "forbidden"); });

    expect(result.status).toBe("rejected");
    expect(box.pending).toBe(false);
  });
});
