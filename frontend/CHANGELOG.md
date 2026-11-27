# frontend 수정 로그

형식: `- YYYY-MM-DD 무엇을 바꿨는지 (관련 파일)`. 최신이 위로 오게 쌓는다. 규칙은 [AGENTS.md](AGENTS.md).

- 2026-11-27 #143 사용자 모양을 backend와 같이 `{ id: 숫자, employeeNo, name, dept }`로 바꾸고 `myUserId` 삭제. 목업 구성원도 숫자 id + 사번, 공유 · 사람 찾기 · 새 페이지 편집자를 숫자 id로, 예전 저장분(사번 기준 공유)은 읽을 때 변환 (src/stores/canvas.ts, src/components/PageInfo.vue, src/components/NewCanvasDialog.vue)
- 2026-11-27 #143 로그인 화면 좌우 2분할(로고 · 우주 배경 / 로그인 폼, 아래 회원가입)과 회원가입 폼 추가, 서버 모드 로그인 · 회원가입 · 내 정보 API 연결(토큰 저장 · 새로고침 복원 · 로그아웃 삭제), 개발 서버 /api 프록시 (src/components/LoginView.vue, src/components/SignupForm.vue, src/stores/canvas.ts, src/sync/http.ts, src/style.css, vite.config.ts)
- 2026-11-26 PR #122 사내 자료 업로드 대상 회사 선택과 D001의 포맷·회사 불일치 표시 추가 (src/components/DataInputDialog.vue, src/sync/http.ts, src/style.css)
- 2026-11-26 PR #122 CodeRabbit 주요 리뷰 수정 및 사내 자료 단건 업로드·작업 폴링·캔버스 권한 기반 복원 연결 (src/App.vue, src/components/, src/local/, src/stores/canvas.ts, src/sync/http.ts)
- 2026-11-26 #107 develop 병합: 동시 편집 연동(#84)을 메모 구조로 맞춤. 서버 이벤트를 add_memo · edit_memo · set_card_memo · set_link_memo로, 글 버전을 memoVersion으로, 시각화 카드는 create_compare(view: chart) + sources로, 선 관계(rel) · set_link · add_chart 삭제. 공유 설정은 소유자만(#100)을 페이지 정보 팝오버에 반영, MenuDrawer 삭제 유지 (src/sync/*, src/stores/canvas.ts, src/App.vue, src/components/PageInfo.vue, docs/frontend/*.md, docs/planning/기획서.md)
- 2026-11-26 #84 동시 편집 연동: 카드·연결 ID UUID, 시각화 카드 저장(add_chart), 서버 연동 계층(src/sync: 묶음 requestId·재전송, 글 baseVersion·충돌, 번호 확인, 원격 반영, STOMP), 다른 사람 커서·접속자 표시, @stomp/stompjs 7.5.0·vitest 5.1.0 추가 (src/sync/*, src/stores/canvas.ts, src/App.vue, src/style.css, package.json, docs/frontend/*.md, docs/planning/기획서.md)
- 2026-11-26 #100 공유 설정은 소유자만 바꾸고 일반 액세스(회사 전체 보기) 삭제, 캔버스 홈 · 메뉴의 공유 범위 표기 삭제 (src/stores/canvas.ts, src/components/ShareDialog.vue, src/components/HomeView.vue, src/components/MenuDrawer.vue, src/style.css, docs/frontend/*.md, docs/planning/기획서.md)
- 2026-11-25 #97 기능 최종 수정: 상단 바 자료 검색 · 프롬프트 · 생성하기, 로고 메뉴 팝업(홈 · 나의 캔버스 · 정보 수정), 자료 검색 화면(대시보드 3개 · 검색 · 인사이트, 선택 칩 + 추가하기, 길게 눌러 끌기, + 사내 자료), 메모 → 프롬프트(카드 · 선 · 프롬프트 카드, 왼쪽 프롬프트 목록), 결과 패널은 생성하기로 열리는 절반 화면 + 프로세스 바, 결과물 PDF · Markdown · 질문 Markdown, 카드 색을 데이터 성격별로, AR 목업 4건 추가, 투자계획서 양식 삭제 (src/App.vue, src/components/*, src/stores/canvas.ts, src/local/*, src/style.css)
- 2026-11-25 #97 문서 반영: 기획서 기능 · 시나리오, 설계서 새로 작성, IA · API 명세 · 다이어그램 (docs/planning/기획서.md, docs/planning/설계서.md, docs/frontend/*.md)
- 2026-11-25 기획서를 캔버스 홈 · 공유 · 새 캔버스 기능에 맞춤: F-07 · F-08 · F-19, 시나리오 1 · 6, 화면 ID 예시, 목업 표시는 문서에만 (docs/planning/기획서.md)
- 2026-11-25 AGENTS.md 문서 확인 규칙에 기획서(docs/planning/기획서.md) 추가 (frontend/AGENTS.md)
- 2026-11-25 CodeRabbit(PR #96) 반영: 권한 없는 캔버스를 소유자로 보던 문제, 보기 권한에서 키보드 · 끌어 놓기로 화면이 바뀌던 문제, 캔버스 전환 뒤 연결 선택 · 자동 배치가 남던 문제 수정 (src/stores/canvas.ts, src/App.vue)
- 2026-11-25 공유 창 찾기 칸에서 Enter로 첫 후보 초대 (src/components/ShareDialog.vue)
- 2026-11-25 개발 서버 포트 5173 고정(strictPort), make_data.py 안내 문구를 AGENTS.md 규칙에 맞춤 (vite.config.ts, scripts/make_data.py)
- 2026-11-25 수정 로그를 AGENTS.md에서 CHANGELOG.md로 분리 (frontend/AGENTS.md, frontend/CHANGELOG.md)
- 2026-11-25 frontend/AGENTS.md 추가 (frontend/AGENTS.md)
