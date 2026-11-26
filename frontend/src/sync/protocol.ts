// 화면 이벤트(snake_case, 목업 계산 엔진과 같은 형식) ↔ 서버 이벤트(camelCase) 변환과 서버 메시지 타입.
// 화면 안에서는 목업(local/server.ts)이 비교·태그·컨텍스트를 계산하므로 기존 형식을 그대로 쓰고,
// 서버로 보낼 때만 규약(docs/backend/api-spec.md 5장) 형식으로 바꾼다.
import type { CanvasEvent } from "../api";

/** 서버 이벤트 한 건. 필드는 camelCase. */
export type ServerEvent = { type: string; [k: string]: unknown };

/** 글 버전. text는 프롬프트 카드 본문, prompt는 카드·선 프롬프트. */
export interface TextVersions { text?: number; prompt?: number }

/** POST /canvases/{id}/events 성공 응답의 data. */
export interface EventsResponse { lastSeq: number; firstChangedLayer: string | null; textVersions: Record<string, TextVersions> }

/** 409 K003 응답의 data: 지금 서버에 저장된 글. */
export interface TextConflict { targetId: string; field: "text" | "prompt"; text: string | null; version: number }

/** /topic/canvases/{id}/events: 커밋된 이벤트 묶음. */
export interface EventsMessage {
  firstSeq: number; lastSeq: number; requestId: string; author: { id: number };
  events: ServerEvent[]; textVersions: Record<string, TextVersions>;
}

/** /topic/canvases/{id}/cursors. 보낸 사람도 받으므로 자기 것은 무시한다. */
export interface CursorMessage { user: { id: number }; x: number; y: number }

/** /topic/canvases/{id}/presence. */
export interface PresenceMessage { type: "join" | "leave"; user: { id: number } }

/** /user/queue/canvases: 나에게만 오는 알림. */
export interface CanvasNotice { type: "revoked" | "deleted" | "role_changed"; canvasId: number; role?: "owner" | "edit" | "view" }

const TO_SERVER: Record<string, string> = {
  card_id: "cardId", table_id: "tableId", doc_id: "docId", link_id: "linkId",
  concept_id: "conceptId", compare_id: "compareId", base_version: "baseVersion",
};
const TO_LOCAL: Record<string, string> = Object.fromEntries(Object.entries(TO_SERVER).map(([k, v]) => [v, k]));
/** 화면에만 있는 필드. 작성자는 서버가 토큰으로 정한다. */
const LOCAL_ONLY = new Set(["author"]);
/** 카드를 새로 만드는 이벤트. 서버에 위치(x, y)를 함께 보낸다. */
export const ADD_TYPES = new Set(["add_card", "add_doc", "add_dash", "add_prompt", "create_compare", "add_chart"]);

/**
 * 화면 이벤트를 서버 이벤트로 바꾼다.
 *
 * @param pos 새 카드면 그 카드의 지금 위치(서버에 저장해 다시 열 때 같은 자리에 둔다)
 */
export function toServerEvent(e: CanvasEvent, pos?: { x: number; y: number }): ServerEvent {
  const out: ServerEvent = { type: e.type };
  for (const [k, v] of Object.entries(e)) {
    if (k === "type" || LOCAL_ONLY.has(k)) continue;
    out[TO_SERVER[k] ?? k] = v;
  }
  if (pos && ADD_TYPES.has(e.type)) {
    out.x ??= pos.x;
    out.y ??= pos.y;
  }
  return out;
}

/** 서버 이벤트(다른 사람의 변경)를 화면 이벤트 형식으로 바꾼다. */
export function fromServerEvent(e: ServerEvent): CanvasEvent {
  const out: CanvasEvent = { type: e.type };
  for (const [k, v] of Object.entries(e)) {
    if (k === "type") continue;
    out[TO_LOCAL[k] ?? k] = v;
  }
  return out;
}

/**
 * 글을 고치는 이벤트면 대상과 글 종류를 돌려준다. 기준 버전을 붙이고 충돌을 고를 때 쓴다.
 * 프롬프트 카드 본문(edit_prompt) → text, 카드 프롬프트(set_card_prompt)·선 프롬프트(set_link_prompt) → prompt.
 */
export function textTarget(e: CanvasEvent): { id: string; field: "text" | "prompt" } | null {
  if (e.type === "edit_prompt") return { id: String(e.card_id), field: "text" };
  if (e.type === "set_card_prompt") return { id: String(e.card_id), field: "prompt" };
  if (e.type === "set_link_prompt") return { id: String(e.link_id), field: "prompt" };
  return null;
}
