// 캔버스 실시간 연결(WebSocket + STOMP). 규약: docs/backend/api-spec.md 5.5·5.6
// - CONNECT 헤더로 토큰을 보낸다(주소에 넣지 않음). 하트비트 10초.
// - 끊기면 라이브러리가 다시 연결하고, 연결될 때마다 다시 구독한 뒤 onConnected로 알린다(화면은 캔버스를 다시 조회).
// - 커서는 많아야 50ms에 한 번만 보낸다(서버도 그보다 잦으면 버림).
import { Client, type IMessage } from "@stomp/stompjs";
import { accessToken, wsUrl } from "./config";
import type { CanvasNotice, CursorMessage, EventsMessage, PresenceMessage } from "./protocol";

export interface RealtimeHandlers {
  onEvents(message: EventsMessage): void;
  onCursor(message: CursorMessage): void;
  onPresence(message: PresenceMessage): void;
  onPresenceSnapshot(users: { id: number }[]): void;
  onNotice(notice: CanvasNotice): void;
  /** (다시) 연결되고 구독을 마쳤을 때. 놓친 변경이 있을 수 있으므로 캔버스를 다시 조회한다. */
  onConnected(): void;
  /** 연결이 끊겼을 때(다시 연결을 시도한다). */
  onDisconnected(): void;
}

export interface RealtimeConnection {
  /** 내 커서 위치(캔버스 좌표)를 보낸다. 50ms보다 잦으면 버린다. */
  sendCursor(x: number, y: number): void;
  /** 연결을 닫는다(캔버스를 닫을 때). */
  close(): void;
}

const CURSOR_INTERVAL_MS = 50;

/** 캔버스 하나의 실시간 연결을 연다. */
export function connectCanvas(canvasId: string, handlers: RealtimeHandlers): RealtimeConnection {
  const json = <T>(m: IMessage) => JSON.parse(m.body) as T;
  let lastCursor = 0;
  const client = new Client({
    brokerURL: wsUrl(),
    reconnectDelay: 3000,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    beforeConnect: async () => {
      const token = accessToken();
      client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    },
    onConnect: () => {
      const topic = `/topic/canvases/${canvasId}`;
      client.subscribe(`${topic}/events`, (m) => handlers.onEvents(json(m)));
      client.subscribe(`${topic}/cursors`, (m) => handlers.onCursor(json(m)));
      client.subscribe(`${topic}/presence`, (m) => handlers.onPresence(json(m)));
      client.subscribe(`/app/canvases/${canvasId}/presence`, (m) => handlers.onPresenceSnapshot(json<{ users: { id: number }[] }>(m).users));
      client.subscribe("/user/queue/canvases", (m) => handlers.onNotice(json(m)));
      handlers.onConnected();
    },
    onWebSocketClose: () => handlers.onDisconnected(),
  });
  client.activate();
  return {
    sendCursor(x, y) {
      const now = Date.now();
      if (!client.connected || now - lastCursor < CURSOR_INTERVAL_MS) return;
      lastCursor = now;
      client.publish({ destination: `/app/canvases/${canvasId}/cursor`, body: JSON.stringify({ x, y }) });
    },
    close() {
      void client.deactivate();
    },
  };
}
