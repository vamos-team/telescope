// 사람 찾기 칸(공유 창 · 새 페이지 창)이 함께 쓰는 검색 상태.
// local 모드는 목업 구성원에서 바로 찾고, server 모드는 입력이 멈추면 GET /users?q=를 부른다(#150).
import { onBeforeUnmount, ref, watch, type Ref } from "vue";
import { useCanvas, type Person } from "../stores/canvas";
import { SYNC_MODE } from "../sync/config";

/** 서버 검색을 부르기 전에 입력이 멈추길 기다리는 시간(ms). 글자마다 요청하지 않게 */
export const SEARCH_DELAY_MS = 250;

/**
 * 검색어(q)와 제외할 사람(exclude)이 바뀌면 다시 찾는다.
 * 늦게 온 응답은 버리고(더 새 검색이 있으면), 화면을 닫으면 진행 중인 요청을 끊는다.
 *
 * @returns found: 찾은 사람(최대 6명), hint: 결과 대신 보여 줄 안내(서버 모드의 빈 검색어 · 실패)
 */
export function usePeopleSearch(q: Ref<string>, exclude: () => Set<number>) {
  const cv = useCanvas();
  const found = ref<Person[]>([]);
  const hint = ref("");
  let timer: ReturnType<typeof setTimeout> | undefined;
  let controller: AbortController | null = null;
  let attempt = 0;

  /** 지금 검색어로 찾는다. 서버 모드는 SEARCH_DELAY_MS 뒤에 부른다. */
  function run() {
    clearTimeout(timer);
    controller?.abort();
    const n = ++attempt;
    if (SYNC_MODE === "server" && !q.value.trim()) {
      found.value = []; hint.value = "사번, 이름, 부서를 입력하세요.";
      return;
    }
    const search = async () => {
      controller = new AbortController();
      try {
        const people = await cv.searchPeople(q.value, exclude(), controller.signal);
        if (n !== attempt) return;
        found.value = people; hint.value = "";
      } catch {
        // 끊은 요청(더 새 검색 · 창 닫기)도 여기로 온다. 그때는 attempt가 이미 바뀌어 있다
        if (n !== attempt) return;
        found.value = []; hint.value = "사람을 찾지 못했습니다. 잠시 후 다시 시도해 주세요.";
      }
    };
    if (SYNC_MODE === "server") timer = setTimeout(search, SEARCH_DELAY_MS);
    else void search();
  }

  watch([q, () => [...exclude()].join(",")], run, { immediate: true });
  onBeforeUnmount(() => { clearTimeout(timer); controller?.abort(); attempt++; });
  return { found, hint };
}
