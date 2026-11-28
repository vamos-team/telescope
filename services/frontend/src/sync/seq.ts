// 다른 사람의 변경 메시지 순서 확인. 규약: docs/backend/api-spec.md 5.4·5.5
// - 서버는 캔버스마다 이벤트 번호(seq)를 1씩 붙인다. 받은 묶음의 firstSeq가 내 마지막 번호 + 1이 아니면
//   중간에 놓친 것이므로 캔버스 전체를 다시 조회한다.
// - 내가 보낸 묶음(requestId가 내 것)도 메시지로 온다. 이미 화면에 반영했으므로 번호만 올린다.
// - 처음 열 때는 구독 → 조회 순서라, 조회가 끝나기 전에 온 메시지는 모아 두었다가 조회한 번호 뒤의 것만 반영한다.
import type { EventsMessage } from "./protocol";

export type SeqDecision =
  | { action: "apply"; message: EventsMessage }  // 다른 사람의 변경: 화면에 반영
  | { action: "own"; message: EventsMessage }    // 내가 보낸 묶음: 번호만 올림
  | { action: "stale" }                          // 이미 반영한 번호: 무시
  | { action: "gap" }                            // 놓친 번호가 있음: 캔버스를 다시 조회
  | { action: "buffered" };                      // 아직 조회 전: 모아 둠

export class SeqTracker {
  private last: number | null = null;
  private buffer: EventsMessage[] = [];
  private readonly mine = new Set<string>();

  /** 내가 보낸 묶음의 requestId를 기억한다(메시지로 돌아오면 번호만 올리기 위해). */
  markMine(requestId: string) {
    this.mine.add(requestId);
  }

  /** 지금 알고 있는 마지막 번호. 조회 전이면 null. */
  get lastSeq(): number | null {
    return this.last;
  }

  /**
   * 캔버스를 조회한 결과의 마지막 번호로 시작한다. 조회 전에 모아 둔 메시지 중 그 뒤의 것을 순서대로 판단해 돌려준다.
   */
  start(lastSeq: number): SeqDecision[] {
    this.last = lastSeq;
    const pending = this.buffer.sort((a, b) => a.firstSeq - b.firstSeq);
    this.buffer = [];
    return pending.map((m) => this.receive(m)).filter((d) => d.action !== "stale");
  }

  /** 다시 조회해야 할 때(재접속, 번호 누락) 조회 전 상태로 돌린다. */
  reset() {
    this.last = null;
    this.buffer = [];
  }

  /** 받은 메시지를 어떻게 처리할지 정한다. */
  receive(message: EventsMessage): SeqDecision {
    if (this.last === null) {
      this.buffer.push(message);
      return { action: "buffered" };
    }
    if (message.lastSeq <= this.last) return { action: "stale" };
    if (message.firstSeq !== this.last + 1) return { action: "gap" };
    this.last = message.lastSeq;
    if (this.mine.delete(message.requestId)) return { action: "own", message };
    return { action: "apply", message };
  }
}
