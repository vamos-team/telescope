import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useCanvas } from "../../stores/canvas";
import { authErrorMessage, signup } from "../http";
import { SyncError } from "../outbox";

// 토큰 저장소를 흉내 낸다: 로그인하면 저장, 로그아웃·401이면 지움
let token: string | null = null;
vi.mock("../config", () => ({
  SYNC_MODE: "server", API_BASE: "",
  accessToken: () => token,
  setAccessToken: (t: string | null) => { token = t; },
}));
vi.mock("../realtime", () => ({ connectCanvas: () => ({ close() {} }) }));

const user = { id: 7, employeeNo: "E1001", name: "김예산", dept: null, job: "재무기획", role: "USER" };
const authData = { accessToken: "jwt-1", tokenType: "Bearer", expiresIn: 3600, user };
const ok = (value: unknown, status = 200) => Response.json({ data: value }, { status });
const fail = (status: number, code: string, message: string) => Response.json({ error: { code, message } }, { status });

beforeEach(() => {
  token = null;
  sessionStorage.clear();
  setActivePinia(createPinia());
});
afterEach(() => vi.unstubAllGlobals());

it("로그인하면 토큰을 저장하고 서버 숫자 ID·사번·이름으로 사용자를 채운다", async () => {
  const fetch = vi.fn(async (_url: string, _init: RequestInit) => ok(authData));
  vi.stubGlobal("fetch", fetch);
  const cv = useCanvas();

  await cv.signIn(" E1001 ", "password-1");

  const [url, init] = fetch.mock.calls[0];
  expect([url, init.method, JSON.parse(String(init.body))]).toEqual(["/api/v1/auth/login", "POST", { employeeNo: "E1001", password: "password-1" }]);
  expect(token).toBe("jwt-1");
  expect(cv.user).toEqual({ id: 7, employeeNo: "E1001", name: "김예산", dept: "" });
  expect(cv.author).toBe("김예산");
});

it("로그인 실패(401 A001)면 오류를 던지고 들어가지 않는다", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => fail(401, "A001", "사번 또는 비밀번호가 올바르지 않습니다.")));
  const cv = useCanvas();

  const err = await cv.signIn("E1001", "wrong").catch((e: unknown) => e);

  expect(authErrorMessage(err)).toBe("사번 또는 비밀번호가 올바르지 않습니다.");
  expect(cv.user).toBeNull();
  expect(token).toBeNull();
});

it("회원가입은 역할 없이 보내고 응답 토큰으로 바로 들어간다", async () => {
  const fetch = vi.fn(async (_url: string, _init: RequestInit) => ok(authData, 201));
  vi.stubGlobal("fetch", fetch);
  const cv = useCanvas();

  await cv.signUp({ employeeNo: "E1001", password: "password-1", name: "김예산", job: "재무기획" });

  const [url, init] = fetch.mock.calls[0];
  expect(url).toBe("/api/v1/auth/signup");
  expect(JSON.parse(String(init.body))).toEqual({ employeeNo: "E1001", password: "password-1", name: "김예산", job: "재무기획" });
  expect(token).toBe("jwt-1");
  expect(cv.user?.id).toBe(7);
});

it("새로고침하면 저장된 토큰으로 본인을 조회해 들어간다", async () => {
  token = "jwt-1";
  const fetch = vi.fn(async (_url: string, _init: RequestInit) => ok(user));
  vi.stubGlobal("fetch", fetch);
  const cv = useCanvas();

  await cv.restoreUser();

  expect(fetch.mock.calls[0][0]).toBe("/api/v1/users/me");
  expect((fetch.mock.calls[0][1].headers as Record<string, string>).Authorization).toBe("Bearer jwt-1");
  expect(cv.user).toMatchObject({ id: 7, employeeNo: "E1001" });
});

it("저장된 토큰이 만료(401)면 토큰을 지우고 로그인 화면에 남는다", async () => {
  token = "expired";
  vi.stubGlobal("fetch", vi.fn(async () => fail(401, "A003", "인증이 만료되었습니다.")));
  const cv = useCanvas();

  await cv.restoreUser();

  expect(cv.user).toBeNull();
  expect(token).toBeNull();
});

it("서버에 닿지 않으면 토큰을 남겨 두고 로그인 화면에 남는다", async () => {
  token = "jwt-1";
  vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch"); }));
  const cv = useCanvas();

  await cv.restoreUser();

  expect(cv.user).toBeNull();
  expect(token).toBe("jwt-1");
});

it("복원 응답을 기다리는 동안 새로 로그인하면 늦게 온 복원 응답(200)은 버린다", async () => {
  token = "old-token";
  let answerMe!: (r: Response) => void;
  vi.stubGlobal("fetch", vi.fn((url: string) => url === "/api/v1/users/me"
    ? new Promise<Response>((r) => { answerMe = r; })
    : Promise.resolve(ok({ ...authData, accessToken: "new-token", user: { ...user, id: 8, employeeNo: "E2002", name: "새사용자" } }))));
  const cv = useCanvas();

  const restoring = cv.restoreUser();
  await cv.signIn("E2002", "password-1");
  answerMe(ok(user));
  await restoring;

  expect(token).toBe("new-token");
  expect(cv.user).toMatchObject({ id: 8, employeeNo: "E2002" });
});

it("복원 응답을 기다리는 동안 새로 로그인하면 늦게 온 401도 새 토큰을 지우지 않는다", async () => {
  token = "old-token";
  let answerMe!: (r: Response) => void;
  vi.stubGlobal("fetch", vi.fn((url: string) => url === "/api/v1/users/me"
    ? new Promise<Response>((r) => { answerMe = r; })
    : Promise.resolve(ok(authData))));
  const cv = useCanvas();

  const restoring = cv.restoreUser();
  await cv.signIn("E1001", "password-1");
  answerMe(fail(401, "A003", "인증이 만료되었습니다."));
  await restoring;

  expect(token).toBe("jwt-1");
  expect(cv.user?.id).toBe(7);
});

it("로그인 응답 전에 회원가입하면 늦게 온 로그인 응답은 버리고 회원가입 결과로 남는다", async () => {
  let answerLogin!: (r: Response) => void;
  vi.stubGlobal("fetch", vi.fn((url: string) => url === "/api/v1/auth/login"
    ? new Promise<Response>((r) => { answerLogin = r; })
    : Promise.resolve(ok({ ...authData, accessToken: "signup-token", user: { ...user, id: 8, employeeNo: "E2002", name: "새사용자" } }, 201))));
  const cv = useCanvas();

  const signingIn = cv.signIn("E1001", "password-1");
  await cv.signUp({ employeeNo: "E2002", password: "password-2", name: "새사용자" });
  answerLogin(ok(authData));
  await signingIn;

  expect(token).toBe("signup-token");
  expect(cv.user).toMatchObject({ id: 8, employeeNo: "E2002" });
});

it("더 새 요청이 있으면 늦게 실패한 이전 로그인은 오류를 던지지 않는다", async () => {
  let answerLogin!: (r: Response) => void;
  vi.stubGlobal("fetch", vi.fn((url: string) => url === "/api/v1/auth/login"
    ? new Promise<Response>((r) => { answerLogin = r; })
    : Promise.resolve(ok(authData, 201))));
  const cv = useCanvas();

  const signingIn = cv.signIn("E1001", "wrong-password");
  await cv.signUp({ employeeNo: "E1001", password: "password-1", name: "김예산" });
  answerLogin(fail(401, "A001", "사번 또는 비밀번호가 올바르지 않습니다."));

  await expect(signingIn).resolves.toBeUndefined();
  expect(token).toBe("jwt-1");
  expect(cv.user?.id).toBe(7);
});

it("로그인 응답 전에 로그아웃하면 늦게 온 로그인 응답으로 들어가지 않는다", async () => {
  let answerLogin!: (r: Response) => void;
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => { answerLogin = r; })));
  const cv = useCanvas();

  const signingIn = cv.signIn("E1001", "password-1");
  await cv.logout();
  answerLogin(ok(authData));
  await signingIn;

  expect(token).toBeNull();
  expect(cv.user).toBeNull();
});

it("저장된 토큰이 있으면 본인 조회가 끝날 때까지 복원 중 상태로 둔다(로그인 화면 깜빡임 방지)", async () => {
  token = "jwt-1";
  let answerMe!: (r: Response) => void;
  vi.stubGlobal("fetch", vi.fn(() => new Promise<Response>((r) => { answerMe = r; })));
  const cv = useCanvas();

  expect(cv.restoring).toBe(true);  // 첫 화면부터 로그인 화면 대신 빈 화면
  const restoring = cv.restoreUser();
  answerMe(ok(user));
  await restoring;

  expect(cv.restoring).toBe(false);
  expect(cv.user?.id).toBe(7);
});

it("복원이 실패해도(만료) 복원 중 상태를 풀어 로그인 화면을 보여 준다", async () => {
  token = "jwt-1";
  vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(fail(401, "A003", "인증이 만료되었습니다."))));
  const cv = useCanvas();

  await cv.restoreUser();

  expect(cv.restoring).toBe(false);
  expect(cv.user).toBeNull();
});

it("토큰이 없으면 서버를 부르지 않는다", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);

  const cv = useCanvas();
  expect(cv.restoring).toBe(false);  // 토큰이 없으면 바로 로그인 화면
  await cv.restoreUser();

  expect(fetch).not.toHaveBeenCalled();
});

it("로그아웃하면 토큰과 사용자를 지운다", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => ok(authData)));
  const cv = useCanvas();
  await cv.signIn("E1001", "password-1");

  await cv.logout();

  expect(token).toBeNull();
  expect(cv.user).toBeNull();
});

it.each([
  [new SyncError(409, "A005", null, "이미 가입된 사번입니다."), "이미 가입된 사번입니다."],
  [new SyncError(400, "C001", null, "password: 크기가 8에서 2147483647 사이여야 합니다"), "password: 크기가 8에서 2147483647 사이여야 합니다"],
  [new SyncError(0, null, null, "TypeError"), "서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."],
  [new Error("사번을 확인하세요"), "사번을 확인하세요"],
])("오류를 화면 문구로 바꾼다 (%#)", (err, message) => {
  expect(authErrorMessage(err)).toBe(message);
});

it("회원가입 중복 사번은 409 A005 SyncError로 받는다", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => fail(409, "A005", "이미 가입된 사번입니다.")));

  const err = await signup({ employeeNo: "E1001", password: "password-1", name: "중복" }).catch((e: unknown) => e);

  expect(err).toBeInstanceOf(SyncError);
  expect((err as SyncError).code).toBe("A005");
});
