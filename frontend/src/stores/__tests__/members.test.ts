import { beforeEach, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { findPeople, roleOf, shareOf, useCanvas, type CanvasMeta } from "../canvas";

const meta = (extra: Partial<CanvasMeta>): CanvasMeta =>
  ({ id: "cv1", name: "예산", dept: "네트워크센터", owner: "이재무", updated: "", updatedBy: "", count: 0, ...extra });

beforeEach(() => {
  sessionStorage.clear();
  setActivePinia(createPinia());
});

it("local 로그인도 서버와 같은 모양(숫자 id + 사번)으로 사용자를 채운다", () => {
  const cv = useCanvas();

  cv.login(" 1002 ");

  expect(cv.user).toEqual({ id: 2, employeeNo: "1002", name: "박망", dept: "네트워크센터" });
  expect(cv.login("9999")).toBe(false);
});

it("공유 정보는 숫자 ID로 읽고 권한을 계산한다", () => {
  const m = meta({ ownerId: 1, share: { "2": "edit", "4": "view" } });

  expect(shareOf(m)).toEqual({ ownerId: 1, share: { "2": "edit", "4": "view" } });
  expect(roleOf(m, { id: 1 })).toBe("owner");
  expect(roleOf(m, { id: 2 })).toBe("edit");
  expect(roleOf(m, { id: 4 })).toBe("view");
  expect(roleOf(m, { id: 3 })).toBeNull();
});

it("예전 저장분(사번으로 저장된 소유자·공유)은 숫자 ID로 바꿔 읽는다", () => {
  const legacy = meta({ ownerId: "1001", share: { "1002": "edit", "2001": "view", "9999": "edit" } });

  expect(shareOf(legacy)).toEqual({ ownerId: 1, share: { "2": "edit", "4": "view" } });  // 없는 사번은 버림
  expect(roleOf(legacy, { id: 2 })).toBe("edit");
});

it("공유 필드가 없는 아주 예전 저장분은 소유자 이름으로 소유자를 찾는다", () => {
  expect(shareOf(meta({ owner: "최예산" }))).toEqual({ ownerId: 4, share: {} });
});

it("예전 세션 사용자({ id: 사번 })로도 새로고침 로그인이 된다", async () => {
  sessionStorage.setItem("telescope-canvas:v1:user", JSON.stringify({ id: "1001", name: "이재무", dept: "네트워크센터" }));
  const cv = useCanvas();

  await cv.restoreUser();

  expect(cv.user).toMatchObject({ id: 1, employeeNo: "1001" });
});

it("사람 찾기는 사번·이름·부서로 찾고 숫자 ID로 제외한다", () => {
  expect(findPeople("1002", new Set(), "네트워크센터").map((m) => m.id)).toEqual([2]);
  expect(findPeople("", new Set([1, 2, 3]), "네트워크센터").some((m) => [1, 2, 3].includes(m.id))).toBe(false);
});
