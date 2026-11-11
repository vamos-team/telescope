# Telescope

## 디렉토리 구조

- `frontend/` — Vue + TypeScript 프론트엔드
- `backend/` — Spring Boot(Gradle, Java 21) 백엔드 API
- `ai/` — Python FastAPI 기반 분석/자료 준비/예상 질문 서비스
- `mcp-server/` — FastMCP 기반 읽기 전용 조회 도구
- `pipelines/` — 데이터 수집·파싱·적재 파이프라인
  - `collectors/` — 외부 데이터 수집
  - `parsers/` — 수집한 데이터 파싱
  - `loaders/` — 파싱 결과 적재
- `infra/jenkins/` — Jenkins CI 서버 구성
- `data/` — 로컬 데이터 저장 경로 (git에 커밋되지 않음)
- `docs/` — 프로젝트 문서
