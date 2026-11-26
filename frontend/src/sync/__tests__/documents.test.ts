import { afterEach, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { api } from "../../api";
import { useCanvas } from "../../stores/canvas";
import { getDocument, uploadDocument, waitJob } from "../http";

vi.mock("../config", () => ({ SYNC_MODE: "server", API_BASE: "", accessToken: () => "test-token", setAccessToken: vi.fn() }));
vi.mock("../realtime", () => ({ connectCanvas: () => ({ close() {} }) }));

const doc = { id: 21, kind: "internal", company: "SKT", title: "설비 예산", publisher: "사내 업로드",
  date: "2026-11-26", summary: "", figures: [], tags: [], url: "", uploaded: true };
const data = (value: unknown, status = 200) => Response.json({ data: value }, { status });
const denied = () => Response.json({ error: { code: "K001", message: "캔버스 권한이 없습니다." } }, { status: 403 });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

it("파일마다 multipart 요청을 한 번 보내고 발행일을 선택적으로 포함한다", async () => {
  const fetch = vi.fn(async (_url: string, _init: RequestInit) => data({ jobId: 11, documentIds: [21] }, 202));
  vi.stubGlobal("fetch", fetch);
  const first = new File(["one"], "budget.pdf"), second = new File(["two"], "budget.pdf");
  await expect(uploadDocument("13", first, "SKT", "2026-11-26")).resolves.toEqual({ jobId: 11, documentIds: [21] });
  await uploadDocument("13", second, "SKT");

  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch.mock.calls.map(([url, init]) => [url, init.method, init.headers])).toEqual([
    ["/api/v1/documents/uploads", "POST", { Authorization: "Bearer test-token" }],
    ["/api/v1/documents/uploads", "POST", { Authorization: "Bearer test-token" }],
  ]);
  const [form1, form2] = fetch.mock.calls.map(([, init]) => init.body as FormData);
  expect([...form1.entries()]).toEqual([["file", first], ["canvasId", "13"], ["company", "SKT"], ["publishedDate", "2026-11-26"]]);
  expect([...form2.entries()]).toEqual([["file", second], ["canvasId", "13"], ["company", "SKT"]]);
});

it("대기 시간과 실행 시간을 따로 세고 실패 메시지를 반환한다", async () => {
  vi.useFakeTimers();
  let status = "QUEUED";
  const fetch = vi.fn(async () => data({ status, startedAt: null, errorMessage: "원본 파일을 찾을 수 없습니다." }));
  vi.stubGlobal("fetch", fetch);

  const result = waitJob(11);
  await vi.advanceTimersByTimeAsync(62_000);
  expect(fetch).toHaveBeenCalledTimes(32);
  status = "RUNNING";
  await vi.advanceTimersByTimeAsync(64_000);
  expect(fetch).toHaveBeenCalledTimes(64);
  status = "FAILED";
  await vi.advanceTimersByTimeAsync(4_999);
  expect(fetch).toHaveBeenCalledTimes(64);
  await vi.advanceTimersByTimeAsync(1);
  await expect(result).resolves.toMatchObject({ status: "FAILED", errorMessage: "원본 파일을 찾을 수 없습니다." });
});

it("성공한 작업의 문서는 숫자 ID를 화면용 문자열로 변환한다", async () => {
  const fetch = vi.fn(async (url: string) => data(url === "/api/v1/jobs/11" ? { status: "SUCCEEDED" } : doc));
  vi.stubGlobal("fetch", fetch);
  await expect(waitJob(11)).resolves.toMatchObject({ status: "SUCCEEDED" });
  await expect(getDocument(21)).resolves.toEqual({ ...doc, id: "21" });
  expect(fetch.mock.calls.map(([url]) => url)).toEqual(["/api/v1/jobs/11", "/api/v1/documents/21"]);
});

it("열 때 문서와 본인 분석을 복원하고 권한 거절 시 문서 캐시를 지운다", async () => {
  let allowed = true;
  const fetch = vi.fn(async (url: string) => {
    if (url === "/api/v1/documents/21") return allowed ? data(doc) : denied();
    return data({ meta: { name: "예산", myRole: "edit" }, lastSeq: 0, warnings: [], links: [],
      cards: [{ id: "a", kind: "doc", docId: 21 }, { id: "b", kind: "doc", docId: 21 }] });
  });
  vi.stubGlobal("fetch", fetch);
  vi.stubGlobal("sessionStorage", { setItem: vi.fn(), getItem: () => "13" });
  vi.stubGlobal("localStorage", { getItem: (key: string) => key === "telescope-analyses:v1"
    ? '{"13|1001":[{"id":"mine"}],"13|1002":[{"id":"other"}]}' : null });
  setActivePinia(createPinia());
  const canvas = useCanvas();
  canvas.user = { id: "1001", name: "이재무", dept: "네트워크센터" };
  try {
    await canvas.restoreCanvas();
    expect(api.docs.get("21")).toMatchObject({ title: doc.title });
    expect(canvas.analyses.map((session) => session.id)).toEqual(["mine"]);
    expect(fetch.mock.calls.filter(([url]) => url === "/api/v1/documents/21")).toHaveLength(1);
    allowed = false;
    await canvas.reloadFromServer();
    expect(api.docs.has("21")).toBe(false);
    expect(canvas.cards).toHaveLength(2);
  } finally {
    canvas.closeServer();
  }
});
