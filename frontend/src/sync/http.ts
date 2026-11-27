// backend HTTP 호출. 응답은 ApiResponse({ data, error })로 온다(docs/backend/api-spec.md 1.2).
import { accessToken, API_BASE } from "./config";
import { SyncError, type Batch } from "./outbox";
import type { Doc } from "../api";
import type { UploadCode } from "../local/validate";
import type { EventsResponse, TextVersions, UserRef } from "./protocol";

/** 캔버스 열기 응답의 카드 한 장(공통 필드 + 종류별 필드). */
export interface ServerCard {
  id: string; kind: string; x: number; y: number;
  text?: string | null; textVersion?: number; memo?: string | null; memoVersion?: number;
  createdBy?: UserRef; [k: string]: unknown;
}

export type ServerRole = "owner" | "edit" | "view";

/** 캔버스 목록 한 줄(목록·생성·이름 변경 응답, 열기 응답의 meta). backend api-spec 4장. */
export interface CanvasSummary {
  id: number; name: string; quarter: string; owner: UserRef; myRole: ServerRole;
  updatedAt: string; updatedBy: UserRef; cardCount: number;
}

/** GET /api/v1/canvases/{id} 응답의 data. */
export interface CanvasSnapshot {
  meta: CanvasSummary;
  cards: ServerCard[];
  links: { id: string; source: string; target: string; memo: string | null; memoVersion: number; memoBy?: UserRef | null }[];
  lastSeq: number;
  warnings: string[];
}

/**
 * backend를 호출하고 data를 돌려준다. 실패하면 상태 코드·에러 코드·data를 담은 SyncError를 던진다.
 * 네트워크 오류는 status 0이다.
 */
async function call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const token = accessToken();
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/api/v1${path}`, {
      method, signal,
      // FormData(파일 업로드)는 브라우저가 multipart 경계를 붙이도록 Content-Type을 비움
      headers: { ...(body instanceof FormData ? {} : { "Content-Type": "application/json" }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
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

// ---------- 인증 (docs/backend/api-spec.md 3장) ----------
/** 로그인한 사용자. `id`는 서버 숫자 ID, 사번은 `employeeNo`. */
export interface AuthUser { id: number; employeeNo: string; name: string; dept: string | null; job: string | null; role: "ADMIN" | "USER" }
/** 로그인·회원가입 응답의 data. */
export interface AuthResult { accessToken: string; tokenType: "Bearer"; expiresIn: number; user: AuthUser }
export interface SignupInput { employeeNo: string; password: string; name: string; dept?: string; job?: string }

/** 사번·비밀번호로 로그인한다. 틀리면 401 A001. */
export function login(employeeNo: string, password: string): Promise<AuthResult> {
  return call("POST", "/auth/login", { employeeNo, password });
}

/** 회원가입하고 로그인과 같은 형식으로 토큰을 받는다. 역할은 서버가 항상 USER로 정한다. 중복 사번은 409 A005. */
export function signup(input: SignupInput): Promise<AuthResult> {
  return call("POST", "/auth/signup", input);
}

/** 저장된 토큰의 본인 정보. 토큰이 없거나 만료되면 401(A002·A003). */
export function fetchMe(): Promise<AuthUser> {
  return call("GET", "/users/me");
}

/** 인증 실패를 화면 문구로 바꾼다. 검증 실패(C001)는 서버 메시지를 그대로 쓴다. */
export function authErrorMessage(err: unknown): string {
  if (!(err instanceof SyncError)) return err instanceof Error && err.message ? err.message : "요청을 처리하지 못했습니다.";
  if (err.status === 0) return "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
  if (err.code === "A001") return "사번 또는 비밀번호가 올바르지 않습니다.";
  if (err.code === "A005") return "이미 가입된 사번입니다.";
  return err.message || "요청을 처리하지 못했습니다.";
}

// ---------- 캔버스 홈·공유·사람 찾기 (backend api-spec 3·4장, #150) ----------
/** 공유 명단의 한 사람. 요청은 userId·role만, 응답에는 이름·부서가 붙는다. */
export interface ShareMember { userId: number; role: "edit" | "view"; name?: string | null; dept?: string | null }
/** GET·PUT /canvases/{id}/shares 응답. 소유자는 members에 없다. */
export interface CanvasShares { ownerId: number; owner: UserRef; members: ShareMember[] }
/** 사람 찾기 결과 한 명(GET /users?q=). 역할은 담기지 않는다. */
export interface PersonResult { id: number; employeeNo: string; name: string; dept: string | null; job: string | null }

/** 내가 볼 수 있는 캔버스(소유 + 초대받은), 최근 수정 순. */
export function listCanvases(signal?: AbortSignal): Promise<CanvasSummary[]> {
  return call("GET", "/canvases?filter=all", undefined, signal);
}

/** 새 캔버스. 만든 사람이 소유자, editorIds는 편집 권한으로 바로 초대된다. 없는 사용자는 404 C002. */
export function createCanvas(input: { name: string; quarter: string; editorIds: number[] }): Promise<CanvasSummary> {
  return call("POST", "/canvases", input);
}

/** 이름 바꾸기(소유자·편집). 보기 권한이면 403 K002. */
export function renameCanvas(canvasId: string, name: string): Promise<CanvasSummary> {
  return call("PATCH", `/canvases/${canvasId}`, { name });
}

/** 캔버스 삭제(소유자만). 다른 권한자는 실시간으로 deleted 알림을 받는다. */
export function deleteCanvas(canvasId: string): Promise<void> {
  return call("DELETE", `/canvases/${canvasId}`);
}

/** 공유 명단(권한자 모두 조회). */
export function getShares(canvasId: string, signal?: AbortSignal): Promise<CanvasShares> {
  return call("GET", `/canvases/${canvasId}/shares`, undefined, signal);
}

/** 공유 명단을 통째로 바꾼다(소유자만). 빠진 사람은 권한이 사라진다. */
export function putShares(canvasId: string, members: { userId: number; role: "edit" | "view" }[]): Promise<CanvasShares> {
  return call("PUT", `/canvases/${canvasId}/shares`, { members });
}

/** 사번·이름·부서 일부로 사람을 찾는다(최대 6명). 빈 검색어면 서버가 빈 목록을 준다. */
export function searchUsers(q: string, signal?: AbortSignal): Promise<PersonResult[]> {
  return call("GET", `/users?q=${encodeURIComponent(q)}`, undefined, signal);
}

/** 활동 로그 한 줄(최근 순 200건). */
export interface ActivityEntry { author: UserRef; text: string; cardId: string | null; createdAt: string }
export function getActivities(canvasId: string, signal?: AbortSignal): Promise<ActivityEntry[]> {
  return call("GET", `/canvases/${canvasId}/activities`, undefined, signal);
}

/** 캔버스 화면 조작 실패를 안내 문구로 바꾼다. */
export function canvasErrorMessage(err: unknown): string {
  if (!(err instanceof SyncError)) return "요청을 처리하지 못했습니다.";
  if (err.status === 0) return "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";
  if (err.code === "K001") return "이 페이지를 볼 권한이 없습니다.";
  if (err.code === "K002") return "이 페이지를 바꿀 권한이 없습니다.";
  if (err.code === "C002") return "찾을 수 없는 사람이나 페이지입니다.";
  return err.message || "요청을 처리하지 못했습니다.";
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

// ---------- 사내 자료 업로드 (한 요청에 파일 하나) ----------
export interface Job { id: number; jobType: string; status: "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED"; startedAt: string | null; finishedAt: string | null; errorMessage: string | null }

export function uploadRejectionReason(err: unknown): UploadCode {
  if (!(err instanceof SyncError) || err.code !== "D001") return null;
  const data = err.data;
  if (!data || typeof data !== "object" || !("reason" in data)) return null;
  return data.reason === "FORMAT_MISMATCH" || data.reason === "COMPANY_MISMATCH" ? data.reason : null;
}

/** POST /documents/uploads: 202 + { jobId, documentIds }. publishedDate(yyyy-MM-dd)가 없으면 서버가 올린 날로 저장. */
export function uploadDocument(canvasId: string, file: File, company: string, publishedDate?: string, signal?: AbortSignal): Promise<{ jobId: number; documentIds: number[] }> {
  if (!/^[1-9]\d*$/.test(canvasId)) return Promise.reject(new Error("서버에서 열린 캔버스를 확인해 주세요."));
  const form = new FormData();
  form.append("file", file); form.append("canvasId", canvasId); form.append("company", company);
  if (publishedDate) form.append("publishedDate", publishedDate);
  return call("POST", "/documents/uploads", form, signal);
}

/** GET /jobs/{id}를 SUCCEEDED · FAILED까지 폴링. 간격 2초, 1분 넘게 RUNNING이면 5초 (backend api-spec 7.2). */
export async function waitJob(jobId: number, signal?: AbortSignal): Promise<Job> {
  let observedRunningAt: number | undefined;
  for (;;) {
    signal?.throwIfAborted();
    const job = await call<Job>("GET", `/jobs/${jobId}`, undefined, signal);
    if (job.status === "SUCCEEDED" || job.status === "FAILED") return job;
    let delay = 2000;
    if (job.status === "RUNNING") {
      observedRunningAt ??= Date.now();
      // 시간대가 생략된 서버 시각은 브라우저의 시계와 직접 비교할 수 없다.
      const startedAt = /(?:Z|[+-]\d{2}:\d{2})$/.test(job.startedAt ?? "") ? Date.parse(job.startedAt!) : NaN;
      if (Date.now() - (Number.isFinite(startedAt) ? startedAt : observedRunningAt) > 60_000) delay = 5000;
    } else {
      observedRunningAt = undefined;
    }
    await new Promise((resolve) => setTimeout(resolve, delay));
  }
}

/** GET /documents/{id}: 카드 데이터(Doc 모양). 업로드 문서는 그 캔버스 권한자만 조회. */
export async function getDocument(id: string | number, signal?: AbortSignal): Promise<Doc> {
  const d = await call<Doc>("GET", `/documents/${id}`, undefined, signal);
  return { ...d, id: String(d.id) };
}
