# frontend 작업 지침

`frontend/`에서 일하는 에이전트가 따르는 규칙이다. 저장소 공용 규칙은 루트 `CLAUDE.md`를 따른다.

## 1. 규칙

- 프론트엔드 기능을 수정할 때 `docs/frontend/demo.html`은 수정하지 않는다. `npm run build:html`도 실행하지 않는다(이 명령이 demo.html을 다시 만든다). 사용자가 요청할 때만 다시 만든다.
- 기능 수정이 끝나면 commit하기 전에 `docs/frontend/` 문서(`ia.md`, `api-spec.md`, `api-diagram.md`, `README.md`)를 한 번 모두 확인하고 바뀐 기능에 맞게 고친다. 사용자가 문서 수정을 요청하면 그대로 반영한다.
- 그 밖의 수정은 `frontend/CHANGELOG.md`에 한 줄씩 기록한다. 형식: `- YYYY-MM-DD 무엇을 바꿨는지 (관련 파일)`, 최신이 위로 오게 쌓는다.

