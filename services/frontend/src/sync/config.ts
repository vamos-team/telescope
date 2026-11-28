// 캔버스 서버 연동 설정. 규약: docs/backend/api-spec.md 5장, docs/frontend/api-spec.md 3장
// local(기본): 지금처럼 브라우저 안 목업과 localStorage만 쓴다.
// server: 캔버스 열기·이벤트 저장은 backend HTTP로, 다른 사람 변경·커서·접속자는 WebSocket(STOMP)으로 주고받는다.
// 로그인(#34)이 붙기 전에는 서버가 모든 요청을 401로 거절하므로 기본은 local이다.

export type SyncMode = "local" | "server";

/** 지금 연동 방식. 환경변수 VITE_CANVAS_SYNC=server 일 때만 서버와 주고받는다. */
export const SYNC_MODE: SyncMode = import.meta.env.VITE_CANVAS_SYNC === "server" ? "server" : "local";

/** backend 주소. 비우면 화면과 같은 출처(배포: Ingress가 /api를 backend로 보냄). Electron 파일 실행이면 지정해야 한다. */
export const API_BASE: string = (import.meta.env.VITE_API_BASE ?? "").replace(/\/$/, "");

/** STOMP 연결 주소. http(s) 주소를 ws(s)로 바꿔 /api/v1/ws를 붙인다. */
export function wsUrl(): string {
  const base = API_BASE || window.location.origin;
  return base.replace(/^http/, "ws") + "/api/v1/ws";
}

const TOKEN_KEY = "telescope-token";

/** 로그인(#34)으로 받은 액세스 토큰. 아직 로그인 API가 없어 비어 있다. */
export function accessToken(): string | null {
  try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
}

/** 로그인(#34)에서 받은 토큰을 저장한다. 로그아웃이면 null. */
export function setAccessToken(token: string | null) {
  try { if (token) sessionStorage.setItem(TOKEN_KEY, token); else sessionStorage.removeItem(TOKEN_KEY); } catch { /* 무시 */ }
}
