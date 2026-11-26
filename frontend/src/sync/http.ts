// backend HTTP 호출. 응답은 ApiResponse({ data, error })로 온다(docs/backend/api-spec.md 1.2).
import { accessToken, API_BASE } from "./config";
import { SyncError, type Batch } from "./outbox";
import type { EventsResponse, TextVersions } from "./protocol";

/** 캔버스 열기 응답의 카드 한 장(공통 필드 + 종류별 필드). */
export interface ServerCard {
  id: string; kind: string; x: number; y: number;
  text?: string | null; textVersion?: number; memo?: string | null; memoVersion?: number;
  createdBy?: { id: number }; [k: string]: unknown;
}

/** GET /api/v1/canvases/{id} 응답의 data. */
export interface CanvasSnapshot {
  meta: { id: number; name: string; quarter: string; myRole: "owner" | "edit" | "view" };
  cards: ServerCard[];
  links: { id: string; source: string; target: string; memo: string | null; memoVersion: number; memoBy?: { id: number } | null }[];
  lastSeq: number;
  warnings: string[];
}

/**
 * backend를 호출하고 data를 돌려준다. 실패하면 상태 코드·에러 코드·data를 담은 SyncError를 던진다.
 * 네트워크 오류는 status 0이다.
 */
async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = accessToken();
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/api/v1${path}`, {
      method,
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (err) {
    throw new SyncError(0, null, null, String(err));
  }
  const json = await res.json().catch(() => null);
  if (!res.ok) {
    throw new SyncError(res.status, json?.error?.code ?? null, json?.data ?? null, json?.error?.message ?? res.statusText);
  }
  return json?.data as T;
}

/** 캔버스를 연다: 카드·연결·마지막 이벤트 번호. */
export function openCanvas(canvasId: string): Promise<CanvasSnapshot> {
  return call("GET", `/canvases/${canvasId}`);
}

/** 이벤트 묶음을 저장한다. 같은 묶음을 다시 보내면 서버가 처음 응답을 돌려준다. */
export function postEvents(canvasId: string, batch: Batch): Promise<EventsResponse> {
  return call("POST", `/canvases/${canvasId}/events`, { requestId: batch.requestId, events: batch.events });
}

/** 열기 응답에서 카드·연결의 글 버전을 꺼낸다(보낼 때 기준 버전으로 쓴다). */
export function versionsOf(snapshot: CanvasSnapshot): Record<string, TextVersions> {
  const versions: Record<string, TextVersions> = {};
  for (const c of snapshot.cards) versions[c.id] = { text: c.textVersion, memo: c.memoVersion };
  for (const l of snapshot.links) versions[l.id] = { memo: l.memoVersion };
  return versions;
}
