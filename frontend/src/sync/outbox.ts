// 서버로 보낼 이벤트 대기열. 규약: docs/backend/api-spec.md 5.2·5.3, 결정 이유: docs/backend/canvas.md D3·D5·D6
// - 묶음마다 requestId를 붙이고, 전송이 실패하면 같은 requestId·같은 내용으로 다시 보낸다(서버가 한 번만 저장).
// - 글 수정은 보내는 순간 알고 있는 버전을 baseVersion으로 붙인다. 같은 묶음 안에서 같은 글을 여러 번 고쳤으면
//   마지막 것만 보낸다(앞의 수정이 저장되면 버전이 올라가 뒤의 수정이 스스로와 충돌하기 때문).
// - 같은 카드의 이동도 묶음 안에서 마지막 위치만 보낸다(서버와 같은 규칙).
import type { CanvasEvent } from "../api";
import { textTarget, toServerEvent, type EventsResponse, type ServerEvent, type TextConflict, type TextVersions } from "./protocol";

/** 서버로 보낼 묶음. 다시 보낼 때도 그대로 쓴다. */
export interface Batch { requestId: string; events: ServerEvent[]; local: CanvasEvent[] }

/** 서버 호출 결과를 나누는 오류. status 0은 네트워크 오류. */
export class SyncError extends Error {
  constructor(readonly status: number, readonly code: string | null, readonly data: unknown, message: string) {
    super(message);
  }
}

export type FlushResult =
  | { status: "idle" }
  | { status: "ok"; response: EventsResponse; batch: Batch }
  | { status: "retry"; error: unknown }               // 네트워크·5xx: 같은 묶음을 다음에 다시 보냄
  | { status: "conflict"; conflict: TextConflict }     // 409 K003: 충돌한 글만 빼고 나머지는 새 묶음으로
  | { status: "rejected"; error: SyncError };          // 권한·형식 오류: 묶음을 버림

export class Outbox {
  private queue: CanvasEvent[] = [];
  private inflight: Batch | null = null;
  /** 카드·연결 ID → 알고 있는 글 버전. 열기 응답, 저장 응답, 다른 사람 메시지로 갱신한다. */
  readonly versions = new Map<string, TextVersions>();

  /**
   * @param newRequestId 묶음 ID 생성기(테스트에서 고정값을 넣는다)
   * @param positionOf 새 카드의 지금 위치(서버에 함께 저장)
   */
  constructor(
    private readonly newRequestId: () => string = () => crypto.randomUUID(),
    private readonly positionOf: (cardId: string) => { x: number; y: number } | undefined = () => undefined,
  ) {}

  /** 보낼 이벤트를 쌓는다. */
  push(e: CanvasEvent) {
    this.queue.push(e);
  }

  /** 보낼 것이 남았는지(다시 보낼 묶음 포함). */
  get pending(): boolean {
    return this.inflight !== null || this.queue.length > 0;
  }

  /** 알고 있는 글 버전을 갱신한다(더 큰 값만 받아들인다). */
  updateVersions(versions: Record<string, TextVersions>) {
    for (const [id, v] of Object.entries(versions)) {
      const cur = this.versions.get(id) ?? {};
      this.versions.set(id, {
        text: Math.max(cur.text ?? 0, v.text ?? 0) || cur.text,
        memo: Math.max(cur.memo ?? 0, v.memo ?? 0) || cur.memo,
      });
    }
  }

  /**
   * 쌓인 이벤트를 보낸다. 앞서 실패한 묶음이 있으면 그 묶음을 같은 requestId로 다시 보낸다.
   *
   * @param send 실제 전송(HTTP). 실패하면 SyncError를 던진다
   */
  async flush(send: (batch: Batch) => Promise<EventsResponse>): Promise<FlushResult> {
    if (!this.inflight) {
      if (!this.queue.length) return { status: "idle" };
      this.inflight = this.build(this.queue.splice(0));
    }
    const batch = this.inflight;
    try {
      const response = await send(batch);
      this.inflight = null;
      this.updateVersions(response.textVersions ?? {});
      return { status: "ok", response, batch };
    } catch (err) {
      if (err instanceof SyncError && err.status === 409 && err.code === "K003") {
        const conflict = err.data as TextConflict;
        this.inflight = null;
        this.versions.set(conflict.targetId, { ...this.versions.get(conflict.targetId), [conflict.field]: conflict.version });
        // 충돌한 글의 수정만 빼고 나머지는 새 묶음으로 다시 보낸다
        this.queue.unshift(...batch.local.filter((e) => {
          const t = textTarget(e);
          return !(t && t.id === conflict.targetId && t.field === conflict.field);
        }));
        return { status: "conflict", conflict };
      }
      if (err instanceof SyncError && err.status >= 400 && err.status < 500) {
        this.inflight = null;
        return { status: "rejected", error: err };
      }
      return { status: "retry", error: err };
    }
  }

  /** 대기열을 비운다(캔버스를 닫거나 권한이 사라질 때). */
  clear() {
    this.queue = [];
    this.inflight = null;
    this.versions.clear();
  }

  /** 이벤트들로 묶음을 만든다: 같은 카드 이동·같은 글 수정은 마지막 것만 남기고, 글 수정에 기준 버전을 붙인다. */
  private build(events: CanvasEvent[]): Batch {
    const lastIndex = new Map<string, number>();
    events.forEach((e, i) => {
      const key = coalesceKey(e);
      if (key) lastIndex.set(key, i);
    });
    const local = events.filter((e, i) => {
      const key = coalesceKey(e);
      return !key || lastIndex.get(key) === i;
    });
    const serverEvents = local.map((e) => {
      const t = textTarget(e);
      const withBase = t ? { ...e, base_version: this.versions.get(t.id)?.[t.field] ?? 0 } : e;
      return toServerEvent(withBase, this.positionOf(String(e.card_id ?? "")));
    });
    return { requestId: this.newRequestId(), events: serverEvents, local };
  }
}

/** 묶음 안에서 마지막 것만 남길 이벤트의 키. 이동은 카드별, 글 수정은 (대상, 글 종류)별. */
function coalesceKey(e: CanvasEvent): string | null {
  if (e.type === "move_card") return `move:${e.card_id}`;
  const t = textTarget(e);
  return t ? `text:${t.id}:${t.field}` : null;
}
