// 브라우저 탭의 움직이는 스텔라 파비콘(#178). 프레임은 src/assets에 두어 번들에 포함한다
// (dev 서버, dist(Electron · 배포), 단일 html 빌드(data URI로 인라인) 모두에서 동작).
// 정적 아이콘(public/favicon.ico)은 index.html이 걸고, 스크립트가 돌면 이 링크가 그 위에 덮어 쓴다.

const FRAME_MS = 110;
const LINK_ID = "app-favicon";

/** 프레임 URL. 파일 이름(stella-00 ~ stella-11) 순서대로 */
const frames: string[] = Object.entries(
  import.meta.glob<string>("./assets/favicon/stella-*.png", { eager: true, query: "?url", import: "default" }),
).sort(([a], [b]) => a.localeCompare(b)).map(([, url]) => url);

let started = false;

/** 파비콘 링크를 하나 만들거나(이미 있으면 재사용) 돌려준다. head 끝에 두어 index.html의 정적 아이콘보다 우선하게 한다 */
function faviconLink(): HTMLLinkElement {
  const found = document.getElementById(LINK_ID) as HTMLLinkElement | null;
  if (found) return found;
  const link = document.createElement("link");
  link.id = LINK_ID;
  link.rel = "icon";
  link.type = "image/png";
  document.head.appendChild(link);
  return link;
}

/**
 * 탭 파비콘을 110ms마다 다음 프레임으로 바꾼다. 여러 번 불러도 한 번만 시작한다.
 * - 움직임 줄이기(prefers-reduced-motion: reduce)면 첫 프레임만 보여 준다(설정을 바꾸면 따라감)
 * - 탭이 숨겨지면 타이머를 멈추고, 다시 보이면 이어서 돈다
 */
export function startFaviconAnimation(): void {
  if (started || typeof document === "undefined" || frames.length === 0) return;
  started = true;

  for (const url of frames) new Image().src = url;  // 미리 불러와 첫 바퀴에 깜빡이지 않게
  const link = faviconLink();
  const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)");
  let index = 0;
  let timer: ReturnType<typeof setInterval> | null = null;

  const show = (i: number) => { index = i; link.href = frames[i]!; };
  const stop = () => { if (timer !== null) { clearInterval(timer); timer = null; } };
  const sync = () => {
    if (reduce?.matches) { stop(); show(0); return; }
    if (document.hidden) { stop(); return; }
    if (timer === null) timer = setInterval(() => show((index + 1) % frames.length), FRAME_MS);
  };

  show(0);
  sync();
  document.addEventListener("visibilitychange", sync);
  reduce?.addEventListener?.("change", sync);
}
