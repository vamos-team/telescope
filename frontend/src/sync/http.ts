// backend HTTP 호출. 응답은 ApiResponse({ data, error })로 온다(docs/backend/api-spec.md 1.2).
import { accessToken, API_BASE } from "./config";
import { SyncError, type Batch } from "./outbox";
import type { Doc } from "../api";
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
