// 메모 입력 칸이 다른 사람이 바꾼 글을 받았을 때의 규칙(MemoEditor). 화면 없이 테스트할 수 있게 뺐다.

export interface MemoDraftState { editing: boolean; draft: string }

/**
 * 받은 글(next)을 입력 칸 상태에 반영한다.
 * - 보기 중이면 받은 글로 바꾼다.
 * - 쓰는 중이어도 아직 아무것도 고치지 않았으면(입력 칸이 이전 글 그대로) 받은 글로 바꾸고, 글이 있으면 보기로 돌린다.
 *   다른 사람이 만든 빈 메모에 그 사람이 처음 쓴 글이 새로고침 없이 보이게 하려는 것이다.
 * - 내가 고치던 중이면 그대로 둔다(덮어쓰지 않음). 겹치면 저장할 때 서버가 409로 알린다.
 *
 * @param prev 바뀌기 전 글
 */
export function receiveText(s: MemoDraftState, next: string | undefined, prev: string | undefined): MemoDraftState {
  const text = next ?? "";
  if (!s.editing) return { editing: false, draft: text };
  if (s.draft !== (prev ?? "")) return s;
  return { editing: !text, draft: text };
}
