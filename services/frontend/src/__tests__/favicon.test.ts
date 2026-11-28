// 움직이는 파비콘(#178). 테스트 환경에 DOM이 없어 document · window · Image를 필요한 만큼만 가짜로 둔다.
import { afterEach, beforeEach, expect, it, vi } from "vitest";

type Link = { id: string; rel: string; type: string; href: string };

let links: Link[];
let listeners: Record<string, () => void>;
let reduceListener: (() => void) | null;
let reduce: { matches: boolean; addEventListener: (t: string, f: () => void) => void };
let preloaded: string[];
let doc: { hidden: boolean };

beforeEach(() => {
  vi.useFakeTimers();
  vi.resetModules();
  links = []; listeners = {}; reduceListener = null; preloaded = [];
  reduce = { matches: false, addEventListener: (_t, f) => { reduceListener = f; } };
  doc = {
    hidden: false,
    getElementById: (id: string) => links.find((l) => l.id === id) ?? null,
    createElement: () => ({ id: "", rel: "", type: "", href: "" }),
    head: { appendChild: (l: Link) => { links.push(l); } },
    addEventListener: (t: string, f: () => void) => { listeners[t] = f; },
  } as unknown as { hidden: boolean };
  vi.stubGlobal("document", doc);
  vi.stubGlobal("window", { matchMedia: () => reduce });
  vi.stubGlobal("Image", class { set src(v: string) { preloaded.push(v); } });
});
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

const start = async () => (await import("../favicon")).startFaviconAnimation();
const frameNo = (href: string) => Number(/stella-(\d+)/.exec(href)?.[1]);

it("프레임 12장을 미리 불러오고 아이콘 링크 하나를 첫 프레임으로 건다", async () => {
  await start();

  expect(preloaded).toHaveLength(12);
  expect(preloaded.map(frameNo)).toEqual([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]);  // 이름순
  expect(links).toHaveLength(1);
  expect(links[0]).toMatchObject({ id: "app-favicon", rel: "icon", type: "image/png" });
  expect(frameNo(links[0]!.href)).toBe(0);
});

it("110ms마다 다음 프레임으로 바뀌고 마지막 다음은 처음으로 돌아간다", async () => {
  await start();

  vi.advanceTimersByTime(110);
  expect(frameNo(links[0]!.href)).toBe(1);
  vi.advanceTimersByTime(110 * 11);
  expect(frameNo(links[0]!.href)).toBe(0);
});

it("여러 번 불러도 링크와 타이머는 하나다", async () => {
  const { startFaviconAnimation } = await import("../favicon");
  startFaviconAnimation();
  startFaviconAnimation();

  expect(links).toHaveLength(1);
  vi.advanceTimersByTime(110);
  expect(frameNo(links[0]!.href)).toBe(1);
});

it("움직임 줄이기 설정이면 첫 프레임에 멈춰 있고, 설정을 끄면 움직인다", async () => {
  reduce.matches = true;
  await start();

  vi.advanceTimersByTime(1000);
  expect(frameNo(links[0]!.href)).toBe(0);

  reduce.matches = false; reduceListener!();
  vi.advanceTimersByTime(110);
  expect(frameNo(links[0]!.href)).toBe(1);
});

it("탭이 숨겨지면 멈추고 다시 보이면 그 프레임부터 이어서 돈다", async () => {
  await start();
  vi.advanceTimersByTime(220);  // 프레임 2

  doc.hidden = true; listeners.visibilitychange!();
  vi.advanceTimersByTime(1000);
  expect(frameNo(links[0]!.href)).toBe(2);

  doc.hidden = false; listeners.visibilitychange!();
  vi.advanceTimersByTime(110);
  expect(frameNo(links[0]!.href)).toBe(3);
});
