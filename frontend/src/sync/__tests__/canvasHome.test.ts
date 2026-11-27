// 서버 모드 캔버스 홈 · 공유 창 · 사람 찾기 · 사람 이름 표시(#150). fetch를 가짜로 바꿔 요청과 화면 상태를 본다.
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { roleOf, useCanvas } from "../../stores/canvas";

vi.mock("../config", () => ({ SYNC_MODE: "server", API_BASE: "", accessToken: () => "jwt", setAccessToken: () => {} }));
vi.mock("../realtime", () => ({ connectCanvas: () => ({ close() {}, sendCursor() {} }) }));

const me = { id: 1, employeeNo: "E1", name: "이재무", dept: "네트워크센터", job: "재무기획" };
const ref = (id: number, name: string | null, dept: string | null = "네트워크센터") => ({ id, name, dept });
const summary = (id: number, name: string, myRole = "owner") => ({
  id, name, quarter: "2026년 1분기", owner: ref(1, "이재무"), myRole, updatedAt: "2026-11-27T09:00:00", updatedBy: ref(2, "최예산"), cardCount: 3,
});
const ok = (data: unknown, status = 200) => (status === 204 ? new Response(null, { status }) : Response.json({ data }, { status }));
const fail = (status: number, code: string, message: string) => Response.json({ error: { code, message } }, { status });
/** 주소 → 응답 표로 fetch를 바꾼다. 부른 순서는 calls로 본다. */
function route(table: Record<string, (init: RequestInit) => Response>) {
  const fetch = vi.fn(async (url: string, init: RequestInit) => {
    const key = `${init.method} ${url}`;
    const hit = table[key];
    if (!hit) throw new Error(`예상하지 않은 요청: ${key}`);
    return hit(init);
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
const bodyOf = (init: RequestInit) => JSON.parse(String(init.body));
const snapshot = (id: number) => ({ meta: summary(id, "예산 검토"), cards: [], links: [], lastSeq: 0, warnings: [] });

beforeEach(() => {
  sessionStorage.clear();
  setActivePinia(createPinia());
  useCanvas().user = { ...me };
});
afterEach(() => vi.unstubAllGlobals());

it("홈 목록은 서버에서 읽고 이름 · 내 권한은 서버 값을 쓴다", async () => {
  route({ "GET /api/v1/canvases?filter=all": () => ok([summary(5, "예산 검토"), summary(6, "공유받은 페이지", "view")]) });
  const cv = useCanvas();

  await cv.loadCanvases();

  expect(cv.canvases.map((m) => [m.id, m.owner, m.updatedBy, m.count])).toEqual([["5", "이재무", "최예산", 3], ["6", "이재무", "최예산", 3]]);
  expect(roleOf(cv.canvases[1], cv.user)).toBe("view");  // 공유 정보가 없어도 서버가 알려 준 권한
});

it("새 페이지는 이름 · 기준 시점 · 편집할 사람을 보내 만들고 바로 연다", async () => {
  const fetch = route({
    "POST /api/v1/canvases": () => ok(summary(7, "새 검토"), 201),
    "GET /api/v1/canvases/7": () => ok(snapshot(7)),
    "GET /api/v1/canvases/7/activities": () => ok([]),
  });
  const cv = useCanvas();

  await cv.newCanvas({ name: " 새 검토 ", quarter: "2026년 1분기", editors: [2, 3] });

  expect(bodyOf(fetch.mock.calls[0][1])).toEqual({ name: "새 검토", quarter: "2026년 1분기", editorIds: [2, 3] });
  expect(cv.canvasId).toBe("7");
  expect(cv.canvases[0].id).toBe("7");
  cv.closeServer();
});

it("이름 바꾸기는 PATCH로 보내고 서버가 정한 이름을 쓴다. 권한이 없으면 안내한다", async () => {
  let allowed = true;
  const fetch = route({ "PATCH /api/v1/canvases/5": () => (allowed ? ok(summary(5, "바뀐 이름")) : fail(403, "K002", "이 캔버스를 수정할 권한이 없습니다.")) });
  const cv = useCanvas();
  Object.assign(cv, { canvasId: "5", canvasName: "예산 검토", serverRole: "edit" });

  await cv.rename(" 바뀐 이름 ");
  expect(bodyOf(fetch.mock.calls[0][1])).toEqual({ name: "바뀐 이름" });
  expect(cv.canvasName).toBe("바뀐 이름");

  allowed = false;
  await cv.rename("또 바꿈");
  expect(cv.canvasName).toBe("바뀐 이름");
  expect(cv.error).toContain("이 페이지를 바꿀 권한이 없습니다.");
});

it("지우면 서버에 DELETE를 보내고 목록에서 뺀다", async () => {
  route({
    "GET /api/v1/canvases?filter=all": () => ok([summary(5, "예산 검토"), summary(6, "다른 페이지")]),
    "DELETE /api/v1/canvases/5": () => ok(null, 204),
  });
  const cv = useCanvas();
  await cv.loadCanvases();

  await cv.removeCanvas("5");

  expect(cv.canvases.map((m) => m.id)).toEqual(["6"]);
  expect(cv.error).toBe("");
});

it("공유 창은 서버 명단을 읽고, 바꾸면 명단 전체를 PUT으로 보낸다", async () => {
  const shares = { ownerId: 1, owner: ref(1, "이재무"), members: [{ userId: 2, role: "edit", name: "최예산", dept: "AIDC사업본부" }] };
  const fetch = route({
    "GET /api/v1/canvases/5/shares": () => ok(shares),
    "PUT /api/v1/canvases/5/shares": (init) => ok({ ...shares, members: bodyOf(init).members.map((m: object) => ({ ...m, name: "이름", dept: null })) }),
  });
  const cv = useCanvas();
  Object.assign(cv, { canvasId: "5", serverRole: "owner" });

  await cv.loadShares();
  expect(cv.serverShares?.members[0].name).toBe("최예산");

  await cv.setShare({ share: { "2": "view", "3": "edit" } });
  expect(bodyOf(fetch.mock.calls[1][1])).toEqual({ members: [{ userId: 2, role: "view" }, { userId: 3, role: "edit" }] });
  expect(cv.serverShares?.members.map((m) => m.userId)).toEqual([2, 3]);
  expect(cv.activity[0].text).toBe("공유 권한을 바꿈");
});

it("소유자가 아니면 공유를 바꾸지 않는다", async () => {
  const fetch = route({});
  const cv = useCanvas();
  Object.assign(cv, { canvasId: "5", serverRole: "edit" });

  await cv.setShare({ share: { "2": "view" } });

  expect(fetch).not.toHaveBeenCalled();
});

it("사람 찾기: 빈 검색어는 서버를 부르지 않고, 찾으면 이미 권한이 있는 사람을 뺀다", async () => {
  const fetch = route({
    "GET /api/v1/users?q=%EC%B5%9C": () => ok([
      { id: 2, employeeNo: "E2", name: "최예산", dept: "AIDC사업본부", job: null },
      { id: 3, employeeNo: "E3", name: "최센터", dept: null, job: "부서장" },
    ]),
  });
  const cv = useCanvas();

  expect(await cv.searchPeople("  ", new Set())).toEqual([]);
  expect(fetch).not.toHaveBeenCalled();

  const found = await cv.searchPeople(" 최 ", new Set([2]));
  expect(found).toEqual([{ id: 3, employeeNo: "E3", name: "최센터", dept: "", job: "부서장" }]);
});

it("열 때 활동 로그를 서버에서 읽고 작성자 이름을 쓴다", async () => {
  route({
    "GET /api/v1/canvases/5": () => ok(snapshot(5)),
    "GET /api/v1/canvases/5/activities": () => ok([
      { author: ref(2, "최예산"), text: "메모를 남김", cardId: null, createdAt: "2026-11-27T09:30:00" },
      { author: ref(9, null), text: "카드를 지움", cardId: null, createdAt: "2026-11-27T09:00:00" },
    ]),
  });
  const cv = useCanvas();

  await cv.open("5");
  await vi.waitFor(() => expect(cv.activity).toHaveLength(2));

  expect(cv.activity.map((a) => a.author)).toEqual(["최예산", "사용자 9"]);  // 사용자가 없으면 ID로
  cv.closeServer();
});

it("커서 · 접속자 메시지의 이름을 기억해 표시하고, 모르는 사람은 ID로 보인다", () => {
  const cv = useCanvas();

  cv.onPresence({ type: "join", user: ref(2, "최예산") });
  cv.onRemoteCursor({ user: ref(3, "오마케"), x: 1, y: 2 });
  cv.onRemoteCursor({ user: ref(1, "이재무"), x: 5, y: 5 });  // 내 커서는 무시

  expect([cv.nameOf(2), cv.nameOf(3), cv.nameOf(4)]).toEqual(["최예산", "오마케", "사용자 4"]);
  expect(Object.keys(cv.cursors)).toEqual(["3"]);
});
