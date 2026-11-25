# frontend 수정 로그

형식: `- YYYY-MM-DD 무엇을 바꿨는지 (관련 파일)`. 최신이 위로 오게 쌓는다. 규칙은 [AGENTS.md](AGENTS.md).

- 2026-11-25 기획서를 캔버스 홈 · 공유 · 새 캔버스 기능에 맞춤: F-07 · F-08 · F-19, 시나리오 1 · 6, 화면 ID 예시, 목업 표시는 문서에만 (docs/planning/기획서.md)
- 2026-11-25 AGENTS.md 문서 확인 규칙에 기획서(docs/planning/기획서.md) 추가 (frontend/AGENTS.md)
- 2026-11-25 CodeRabbit(PR #96) 반영: 권한 없는 캔버스를 소유자로 보던 문제, 보기 권한에서 키보드 · 끌어 놓기로 화면이 바뀌던 문제, 캔버스 전환 뒤 연결 선택 · 자동 배치가 남던 문제 수정 (src/stores/canvas.ts, src/App.vue)
- 2026-11-25 공유 창 찾기 칸에서 Enter로 첫 후보 초대 (src/components/ShareDialog.vue)
- 2026-11-25 개발 서버 포트 5173 고정(strictPort), make_data.py 안내 문구를 AGENTS.md 규칙에 맞춤 (vite.config.ts, scripts/make_data.py)
- 2026-11-25 수정 로그를 AGENTS.md에서 CHANGELOG.md로 분리 (frontend/AGENTS.md, frontend/CHANGELOG.md)
- 2026-11-25 frontend/AGENTS.md 추가 (frontend/AGENTS.md)
