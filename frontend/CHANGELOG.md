# frontend 수정 로그

형식: `- YYYY-MM-DD 무엇을 바꿨는지 (관련 파일)`. 최신이 위로 오게 쌓는다. 규칙은 [AGENTS.md](AGENTS.md).

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
