# 프런트엔드 API 명세 (초안)

v02-canvas 데모(코드 `frontend/`, 빌드 `docs/frontend/demo.html`)가 화면에서 부르는 기능을 서버 API로 옮겨 적은 문서다. 데모는 `frontend/src/api.ts`의 `api` 객체가 브라우저 안 구현(`local/server.ts`, `local/ai.ts`)을 부른다. 실제 서비스에서는 같은 자리에서 아래 API를 부른다.

- 근거 코드: `frontend/src/api.ts`(타입), `stores/canvas.ts`(캔버스·이벤트), `local/server.ts`(검색·비교·컨텍스트), `local/ai.ts`(검토·정리 글·질문·평가), `local/validate.ts`(업로드 검증), `local/dashboard.ts`·`local/dart.ts`(대시보드 패널)
- 경로와 필드는 **제안**이다. backend·ai 담당자와 합의한 뒤 확정한다. `CLAUDE.md` 7장의 미정 항목은 여기서 정하지 않는다.

## 1. 공통 규칙

| 항목 | 규칙 |
|---|---|
| 기반 경로 | `/api/v1` (`docs/backend/job.md`와 같음) |
| 인증 | 로그인 응답의 JWT를 `Authorization: Bearer <token>`으로 보냄. 로그인 외 모든 API에 필요 |
| 응답 형식 | `{ "data": ..., "error": null }`, 실패 시 `{ "data": null, "error": { "code", "message" } }` (`ApiResponse`) |
| 필드 이름 | camelCase. 데모 타입의 snake_case(`table_id`, `card_id`)는 camelCase(`tableId`, `cardId`)로 바꿔 받음 |
| 시각 | ISO 8601 문자열 (`2026-11-25T09:30:00`) |
| 금액 단위 | 원본 표는 공시 단위 그대로(`unit`). 비교·근거 값은 서버가 십억원으로 변환(`UNIT_TO_BIL`). 화면은 표시만 바꿈 |
| 오래 걸리는 작업 | `202` + `{ jobId }`를 받고 `GET /api/v1/jobs/{id}`를 `SUCCEEDED`·`FAILED`까지 폴링한 뒤 결과 조회 API를 부름. 콜백 없음 |
| 권한 | 페이지(캔버스)마다 소유자 · 편집 · 보기 권한(3.9). 공유 설정은 소유자만 바꾼다. 일반 액세스(회사 전체 보기)는 없다. 권한 없는 페이지는 403. 분석 세션은 요청한 본인만 조회. 공용 문서 수집·삭제는 관리자만 |
| 용어 | 화면의 "페이지" = 캔버스 + 분석. API 경로는 `canvases`를 그대로 쓴다 |

### 1.1 화면별 호출 요약

| 화면 | 기능 | API | 데모 함수 |
|---|---|---|---|
| 로그인 | 로그인 | `POST /auth/login` | `canvas.login` |
| 로그인 | 내 정보 | `GET /users/me` | `restoreUser` |
| 페이지 홈 (C00) | 볼 수 있는 페이지 목록·삭제 | `GET /canvases`, `DELETE /canvases/{id}` | `canvases`, `removeCanvas`, `thumb` |
| 새 페이지 (C14) | 이름 · 기준 시점 · 편집할 사람으로 생성, 사람 찾기 | `POST /canvases`, `GET /users?q=` | `newCanvas`, `findPeople` |
| 헤더 | 페이지 이름 변경 | `PATCH /canvases/{id}` | `rename` |
| 페이지 정보 (C13) | 사내 사람 찾기 | `GET /users?q=` | `ALL_MEMBERS` 상수 |
| 페이지 정보 (C13) | 정보 · 권한 조회·변경 | `GET /canvases/{id}`, `GET/PUT /canvases/{id}/shares` | `meta`, `members`, `setShare` |
| 캔버스 (C10) | 페이지 열기 | `GET /canvases/{id}` | `canvas.open` |
| 캔버스 (C10) | 카드·연결·메모 조작, 표 생성 · 시각화 생성 | `POST /canvases/{id}/events` | `api.postEvents` |
| 왼쪽 사이드바 | 메모 목록 (카드 메모 · 메모 카드 · 선 메모) | (캔버스 상태에서 계산, API 없음) | `cardTitle`, `notes` |
| 캔버스 | 같은 개념 자동 연결 | `GET /canvases/{id}/card-tags` | `api.cardTags` |
| 자료 검색 · 대시보드 · 회사 재무 탭 | 대시보드 패널 (KPI · 차트 · 표 · 자산 계정) | `GET /dashboard` | `local/dashboard.ts` `panelOf`, `LAYOUT` |
| 자료 검색 · 통합 검색 | 공시 재무 데이터 검색 (입력 즉시, 아래 두 줄과 같은 `q`로 함께 부름) | `GET /filings/search` | `api.searchQuestion` |
| 자료 검색 · 통합 검색 | IR·AR · 뉴스 검색 | `GET /documents?kind=ir,ar`, `GET /documents?kind=news` | `api.searchDocs` |
| 자료 검색 · 행 고르기 / 원본 보기 | 공시 표 조회 | `GET /filings/tables/{tableId}` | `api.table`, `api.noteTable` |
| 자료 검색 · 인사이트 | 3사 비교 인사이트(분기) | `GET /insights` | `api.analysis.insights`, `api.peerFacts` |
| 자료 검색 · 인사이트 | 연간 인사이트 | `GET /dashboard` (인사이트 패널) | `local/dart.ts` `ANNUAL_INSIGHTS` |
| 자료 검색 · 인사이트 | 관련 뉴스 추천 (썸네일) | `GET /documents?kind=news` | `api.recommend` |
| 데이터 입력 (C16) | 사내 자료를 파일별로 업로드하고 처리 결과로 카드 추가 | `POST /documents/uploads`, `GET /jobs/{id}`, `GET /documents/{id}` | `DataInputDialog.vue`, `sync/http.ts`, 로컬은 `validate.checkUpload` |
| 비교·시각화 카드 | 표 비교 결과 (확정 · 거부 반영) | `GET /canvases/{id}/compares/{cardId}` | `api.compare` |
| 분석 (C20) | 내 분석 세션 목록 | `GET /canvases/{id}/analyses` | `canvas.analyses` (브라우저 저장) |
| 분석하기 → 검토 (C21) | 메모 분류 · 주장 · 근거 판정 · 지적 · AI 인사이트 + 정리 글 | `POST /canvases/{id}/analyses` → job | `ai.review` (+ `notes`, `api.evidence`) |
| 분석하기 → 질문 (C22) | 질문 생성 (같은 작업의 두 번째 단계) | `POST /canvases/{id}/analyses` → job | `ai.questions` |
| 평가 (C23) | 다음 분기 자료가 들어온 뒤 평가 | `POST /analyses/{id}/evaluations` | `ai.evaluate`, `canvas.attachEval` |
| 검토 · 질문 | 정리 글 복사 · Markdown, 질문 리포트 Markdown | (화면에서 만듦, API 없음) | clipboard, Blob 내려받기 |

데모에만 있는 기능은 API를 두지 않는다: 로그인 화면의 데모 계정 미리 입력, `예시 불러오기`(`api.meta.example`), 평가의 `다음 분기 자료 반영 (데모)` 버튼, 홈 미리보기(`thumb`, 실제 서비스는 서버가 만든 썸네일 또는 카드 좌표), `api.warmup`·`api.ask`(LLM 없음).

## 2. 인증·사용자

### 2.1 `POST /api/v1/auth/login`

사번과 비밀번호로 로그인한다. 데모는 비밀번호를 검사하지 않는다.

| 요청 | 설명 | 타입 |
|---|---|---|
| `employeeNo` | 사번 | string |
| `password` | 비밀번호 | string |

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `accessToken` | JWT | string |
| `user` | `{ id, name, dept, job, role }` | object |

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 401 | 사번 없음 또는 비밀번호 불일치 | "사번을 확인하세요" |

로그아웃은 서버 호출 없이 토큰을 버린다. 그 전에 남은 이벤트를 보낸다(`canvas.logout`의 `flush`).

### 2.2 `GET /api/v1/users/me`

새로고침 뒤 토큰으로 사용자를 복원한다(`restoreUser`). 응답은 2.1의 `user`.

### 2.3 `GET /api/v1/users?q={검색어}`

공유 창(C13)과 새 캔버스 창(C14)에서 사내 사람을 찾는다. 사번 · 이름 · 부서 일부로 찾고 최대 6명을 돌려준다. `q`가 비어 있으면 추천(요청한 사람과 같은 부서 먼저)을 돌려준다. `excludeCanvas={id}`를 주면 그 캔버스에 이미 권한 있는 사람을 뺀다.

```json
{ "data": [{ "id": "2001", "name": "최예산", "dept": "AIDC사업본부", "job": "재무기획" }], "error": null }
```

## 3. 캔버스

캔버스 하나가 데모의 세션(`sessionId`) 하나다. 서버 이벤트 로그가 원본이고, 화면은 로컬 상태를 먼저 바꾼 뒤 이벤트를 묶어 보낸다.

### 3.1 `GET /api/v1/canvases`

내가 볼 수 있는 캔버스 목록(캔버스 홈 C00). 소유한 것과 초대받은 것을 담는다. 최근 수정 순.

| 쿼리 | 설명 | 타입 |
|---|---|---|
| `filter` | `all`(최근) / `mine`(내가 만든) / `shared`(공유받은). 기본 `all` | string? |
| `q` | 캔버스 이름 검색 | string? |

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `id` | 캔버스 ID | string |
| `name` | 이름 | string |
| `dept` | 부서 | string |
| `owner` | 만든 사람 | string |
| `updatedAt` | 마지막 수정 시각 | string |
| `updatedBy` | 마지막 수정자 | string |
| `cardCount` | 카드 수 | number |
| `ownerId` | 소유자 사번 | string |
| `myRole` | 내 권한 `owner` / `edit` / `view` | string |
| `quarter` | 분기 (예: `2026년 1분기`). 상단 제목 옆에 표시 | string |
| `thumbnail` | 홈 미리보기. 카드 `{ kind, x, y }` 목록 또는 이미지 URL (정해야 함) | object |

### 3.2 `POST /api/v1/canvases`

| 요청 | 설명 | 타입 |
|---|---|---|
| `name` | 이름. 없으면 `"{부서} 캔버스 {n}"` | string? |
| `quarter` | 분기 (예: `2026년 1분기`) | string |
| `editorIds` | 편집 권한을 줄 사번 목록 (C14 편집할 사람) | string[] |

응답은 3.1의 항목 하나. `201`. 만든 사람이 소유자가 되고, `editorIds`는 `edit`으로 초대된다.

### 3.3 `PATCH /api/v1/canvases/{id}` · `DELETE /api/v1/canvases/{id}`

`PATCH`는 `{ name }`만 받는다. 빈 문자열이면 `"제목 없는 캔버스"`. 소유자 · 편집 권한만. `DELETE`는 소유자만, `204`. 지운 캔버스를 보고 있었으면 화면이 캔버스 홈으로 간다.

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 이름 변경: 보기 권한 / 삭제: 소유자 아님 | 화면에서 버튼을 숨김 |

### 3.4 `GET /api/v1/canvases/{id}`

캔버스를 연다. 데모는 저장한 이벤트를 새 세션에 다시 보내 상태를 복원하지만, 서버는 복원한 상태를 바로 돌려준다.

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `meta` | 3.1의 항목 | object |
| `cards` | 카드 목록 (3.5의 `Card`) | Card[] |
| `links` | `{ id, source, target, memo? }` 목록 | Link[] |
| `lastSeq` | 마지막 이벤트 번호 | number |

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 권한 없음(공유가 풀림 등) | "이 캔버스를 볼 권한이 없습니다." 후 캔버스 홈 |
| 404 | 없는 캔버스 | — |
| 409 | 카드가 가리키는 표·문서가 사라짐 | "'{이름}' 캔버스 일부를 복원하지 못했습니다. 자료가 바뀌었을 수 있습니다." |

### 3.5 카드·연결 타입

좌표(`x`, `y`)는 화면 배치용이며 컨텍스트에 들어가지 않는다. 카드·연결 ID는 화면이 UUID로 만든다(여러 사람이 동시에 만들어도 겹치지 않게).

여러 사람이 고칠 수 있는 글은 버전을 갖는다: 메모 카드 본문 `textVersion`, 카드 메모 `memoVersion`, 선 메모 `memoVersion`(캔버스 열기 응답에 함께 온다).

| `kind` | 필드 | 설명 |
|---|---|---|
| `table` | `tableId`, `rows`, `cols`, `vcol`, `edits`, `sort` | 공시 표 카드. `vcol`은 비교 기준 열, `edits`는 `"r,c": 값` |
| `compare` | `sources` | 비교 카드(표 생성). 원본 표 카드 ID 목록 |
| `chart` | `sources`, `compareId?` | 시각화 카드(시각화 생성). 서버에는 `create_compare`(`view: chart`)로 등록해 같은 비교 계산을 씀. `compareId`는 비교 카드에서 만들었을 때 |
| `doc` | `docId` | IR·AR·뉴스·사내 자료 카드 |
| `memo` | `author`, `text` | 메모 카드. 종류 구분 없음 |
| `dash` | `panel` | 대시보드 카드. `panel`은 4.4 패널 ID |

메모 카드를 뺀 모든 카드는 `memo`(카드 메모)를 가질 수 있다. 연결은 `source`, `target`, `memo`(선 메모)만 갖는다. 관계 종류(근거 · 설명 · 반박)는 저장하지 않고 분석에서 에이전트가 판단한다.

메모 3종(카드 메모 · 메모 카드 · 선 메모)은 모두 사용자가 쓴 글이다. 분석(7.2)에서 에이전트가 문장마다 지시 · 근거 · 의견으로 가린다.

### 3.6 `POST /api/v1/canvases/{id}/events`

카드·연결 조작을 묶어 보낸다. 화면은 150ms 동안 모은 이벤트를 한 번에 보낸다. 실패하면 화면이 같은 묶음을 **같은 `requestId`로** 다시 보낸다(서버는 한 번만 저장).
저장 규칙은 `docs/backend/api-spec.md` 5장, 결정 이유는 `docs/backend/canvas.md`.

| 요청 | 설명 | 타입 |
|---|---|---|
| `requestId` | 묶음마다 화면이 만드는 UUID. 같은 묶음을 다시 보낼 때는 같은 값 | string |
| `events` | 이벤트 목록. 작성자는 토큰의 사용자 | Event[] |

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 보기 권한 (`K002`) | "보기 권한만 있습니다." (화면은 보기 권한이면 편집 도구를 숨기고 보내지 않음) |
| 409 | 다른 사람이 같은 글을 먼저 고침 (`K003`). `data`: `{ targetId, field, text, version }` | "다른 사람이 먼저 고쳤습니다. 최신 글로 바꿨으니 확인한 뒤 다시 고쳐 주세요." 충돌한 글만 최신으로 바꾸고 나머지 조작은 새 `requestId`로 다시 보냄 |

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `lastSeq` | 반영 후 마지막 이벤트 번호 | number |
| `firstChangedLayer` | 처음 바뀐 컨텍스트 층 (`L1_sources` / `L2_workspace` / `L3_edits` / `null`) | string \| null |
| `textVersions` | 이번 묶음으로 바뀐 글의 새 버전 `{ 카드·연결 ID: { text?, memo? } }` | object |

이벤트 종류 (`type`과 필드):

| `type` | 필드 | 활동 로그 문구 |
|---|---|---|
| `add_card` | `cardId`, `tableId`, `rows`, `cols` | 공시 표를 올림 |
| `add_rows` | `cardId`, `rows` | — |
| `remove_row` | `cardId`, `r` | — |
| `select_period` | `cardId`, `c` | — |
| `sort_card` | `cardId`, `key`, `desc` | — |
| `edit_cell` | `cardId`, `r`, `c`, `value` | 값을 수정함 |
| `revert_card` | `cardId` | — |
| `add_doc` | `cardId`, `docId` | 자료를 올림 |
| `add_dash` | `cardId`, `panel` | 대시보드를 올림 |
| `add_memo` | `cardId`, `text`, `author` | 메모를 남김. 서버 모드에서는 `author`를 보내지 않고 서버가 토큰으로 정함 |
| `edit_memo` | `cardId`, `text`, `baseVersion` | 메모 카드 본문을 고침 |
| `set_card_memo` | `cardId`, `text`, `baseVersion` | 카드 메모를 고침. 빈 값이면 지움 |
| `set_link_memo` | `linkId`, `text`, `baseVersion` | 선 메모를 고침. 빈 값이면 지움 |
| `create_compare` | `cardId`, `sources`, `view?` | 비교 카드(`view: compare`, 기본) 또는 시각화 카드(`view: chart`)를 만듦 |
| `confirm_mapping` | `conceptId`, `company`, `cells`, `accepted` | 계정 대응 확정(`true`) · 거부(`false`) · 취소(`null`). 같은 개념 · 회사의 이전 값을 덮어씀 |
| `add_link` | `linkId`, `source`, `target` | 카드를 연결함 |
| `remove_link` | `linkId` | 연결을 지움 |
| `remove_card` | `cardId` | 카드를 지움. 서버가 그 카드의 연결과 비교 원본에서도 뺌 |
| `move_card` | `cardId`, `x`, `y` | 좌표만 저장. 컨텍스트·활동 기록에 영향 없음 |

예전 저장본 읽기: 프롬프트 카드(`kind: prompt`)는 메모 카드로, 카드 · 연결의 `prompt`는 `memo`로, 이벤트 `add_prompt` · `edit_prompt` · `set_card_prompt` · `set_link_prompt`는 `add_memo` · `edit_memo` · `set_card_memo` · `set_link_memo`로 바꿔 재생한다. `set_memo_type`, `set_link`와 `add_link`의 `rel`, `add_memo`의 `memoType`은 무시한다.

```json
{ "requestId": "6f1c2a1e-6c1e-4d0f-9a43-7d3f8a0b9c21",
  "events": [
  { "type": "add_card", "cardId": "0d9f…", "tableId": "SKT_2026Q1_is", "rows": [3, 5], "cols": [0, 1], "x": 120, "y": 80 },
  { "type": "add_link", "linkId": "5b2e…", "source": "0d9f…", "target": "a71c…" },
  { "type": "set_link_memo", "linkId": "5b2e…", "text": "이 IR 수치가 표의 감소를 설명함", "baseVersion": 0 },
  { "type": "create_compare", "cardId": "c3d4…", "sources": ["0d9f…", "e8a1…"], "view": "chart", "x": 740, "y": 640 }
] }
```

- 새 카드를 만드는 이벤트(`add_card`, `add_doc`, `add_dash`, `add_memo`, `create_compare`)는 위치(`x`, `y`)를 함께 보낸다. 시각화 카드도 `create_compare`(`view: chart`)로 만든다.
- `baseVersion`은 고치기 시작할 때 알고 있던 그 글의 버전이다. 한 묶음 안에서 같은 글을 여러 번 고쳤으면 마지막 것만 보낸다(같은 카드의 이동도 마지막 위치만).
- 화면 안(목업 계산 엔진)에서는 기존 snake_case 형식(`card_id`)을 쓰고, 서버로 보낼 때만 이 형식으로 바꾼다(`frontend/src/sync/protocol.ts`).

### 3.7 `GET /api/v1/canvases/{id}/activities`

활동 로그. 최근 순, 최대 200건.

```json
{ "data": [{ "author": "박망", "text": "카드를 연결함", "cardId": null, "createdAt": "2026-11-25T09:31:00" }], "error": null }
```

### 3.8 `GET /api/v1/canvases/{id}/card-tags`

카드별 개념 태그. 같은 개념의 카드끼리 점선으로 자동 연결할 때 쓴다. 이벤트를 보낸 뒤마다 다시 부른다.

```json
{ "data": { "c1": ["da", "capex"], "c4": ["da"] }, "error": null }
```

### 3.9 `GET /api/v1/canvases/{id}/shares` · `PUT /api/v1/canvases/{id}/shares`

공유 창(C13). `GET`은 권한 있는 사람 전원(활동 로그 "이 캔버스 사람"에도 씀), `PUT`은 소유자만 부른다.

| 필드 | 설명 | 타입 |
|---|---|---|
| `ownerId` | 소유자 사번 (`GET`만, 바꿀 수 없음) | string |
| `members` | 직접 초대한 사람 `{ userId, role }`. `role`은 `edit` / `view`. 목록에서 빠지면 권한 삭제 | object[] |

`GET` 응답에는 소유자와 초대한 사람을 펼친 `people[]`(`{ id, name, dept, job, role }`)를 함께 준다.

```json
{ "members": [{ "userId": "2001", "role": "view" }] }
```

권한 계산 순서: 소유자 → 직접 초대 권한. 둘 다 아니면 권한 없음. 바꾼 결과는 활동 로그에 "공유 권한을 바꿈"으로 남는다.

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 소유자가 아닌 사람(편집 · 보기 권한)이 `PUT` | 화면은 공유 창을 읽기 전용으로 보여 줌 ("공유 설정은 소유자만 바꿀 수 있습니다.") |
| 404 | 없는 사번 | "찾는 사람이 없거나 이미 권한이 있습니다." |

### 3.10 실시간 연결 (WebSocket · STOMP)

다른 사람의 변경·커서·접속자는 WebSocket(STOMP)으로 받는다. 주소·메시지 형식은 `docs/backend/api-spec.md` 5.5·5.6, 선정 이유는 11장.

| 화면 동작 | 방법 |
|---|---|
| 연결 | `wss://{호스트}/api/v1/ws`, `CONNECT` 헤더 `Authorization: Bearer {토큰}`. 끊기면 3초 뒤 다시 연결 |
| 캔버스 열기 | 구독(`/topic/canvases/{id}/events` 등) → `GET /canvases/{id}` → `lastSeq` 뒤 메시지만 반영 |
| 다른 사람 변경 | `firstSeq`가 내 마지막 번호 + 1이면 반영, 아니면 캔버스를 다시 조회. 내 `requestId`면 번호만 올림 |
| 커서 | 마우스 위치를 캔버스 좌표로 `/app/canvases/{id}/cursor`에 50ms마다 최대 1번. 받은 커서 중 내 것은 무시 |
| 접속자 | `/app/canvases/{id}/presence`로 지금 목록을 한 번 받고, `/topic/canvases/{id}/presence`로 들어옴·나감 반영 |
| 권한 알림 | `/user/queue/canvases`: `revoked`·`deleted`면 캔버스 홈으로, `role_changed`면 편집 도구를 권한에 맞춤 |

화면 구현은 `frontend/src/sync/`(연동 계층)이고, 환경변수 `VITE_CANVAS_SYNC=server`일 때만 켜진다(기본은 목업). 로그인(#34) 전에는 서버가 연결을 거절하므로 목업으로 동작한다.

## 4. 공시

### 4.1 `GET /api/v1/filings/search`

자료 추가 `공시` 탭. 질문 문장을 회사명과 낱말로 나눠 찾고, 회사가 나오면 그 회사 표만 돌려준다. 표 단위로 묶어 점수 순.

| 쿼리 | 설명 | 타입 |
|---|---|---|
| `q` | 검색어 또는 질문 문장. 빈 값이면 호출하지 않음 | string |

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `tableId`, `company`, `title`, `basis`, `period`, `unit` | 표 정보. `basis`는 연결/별도 | string |
| `score` | 점수 | number |
| `hits[]` | 찾은 행 `{ r, label, grp, why }`. `why`는 찾은 이유 | object[] |

### 4.2 `GET /api/v1/filings/tables/{tableId}`

공시 표 원본. 표 카드, 원본 보기(행 고르기), 비교 카드가 쓴다. 화면은 같은 표를 한 번만 받아 둔다.

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `tableId`, `rceptNo`, `company`, `title`, `noteNo` | 표 식별. `rceptNo`는 DART 접수번호, `noteNo`는 주석 번호 | string |
| `basis`, `period`, `unit` | 연결/별도, 기간, 단위 | string |
| `caption[]` | 표 위 설명 | string[] |
| `cols[]` | 열 이름 (`1Q26`, `1Q25` 등) | string[] |
| `rows[]` | `{ r, label, path, grp, depth, notes, values, acodes }`. `notes`는 이 행이 가리키는 주석 번호 | Row[] |
| `headerRows`, `labelCols`, `raw` | 원본 표 그리기용 | — |

| HTTP | 상황 |
|---|---|
| 404 | 없는 표 |

행의 주석 번호를 눌러 주석 표로 갈 때(데모 `api.noteTable(rceptNo, noteNo)`)는 `GET /api/v1/filings/tables?rceptNo=&noteNo=`로 `tableId`를 찾는다. 없으면 `data: null`.

### 4.3 `GET /api/v1/insights`

자료 검색 `인사이트` 탭의 AI 해석 비교 테이블. 카드마다 3사 수치 증감, AI 해석, 근거 뉴스를 담는다. 수치는 코드로 계산하고 LLM은 해석 문장만 쓴다(설계서 3장).

| 쿼리 | 설명 | 타입 |
|---|---|---|
| `q` | 주제. 있으면 그 낱말이 닿는 개념의 카드만 (화면은 지금 보내지 않음: 인사이트 탭은 캔버스 개념과 겹치는 순으로만 정렬) | string? |
| `asOfDate` | 기준 시점. 이 날짜 이전 공시·뉴스만 | string |

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `id`, `title` | 카드 식별, 제목 | string |
| `concepts[]` | 개념 ID | string[] |
| `ai` | AI 해석 문장 | string |
| `news[]` | 근거 문서 ID (5.1의 `id`) | string[] |
| `facts[]` | `{ concept, name, rows: [{ company, cur, prv }] }`. 기준 분기·전년 동기, 십억원 | object[] |
| `tables[]` | 캔버스에 올릴 때 함께 올릴 3사 표 ID | string[] |

### 4.4 `GET /api/v1/dashboard`

자료 검색 `대시보드` · `회사 재무` · `인사이트`(연간) 탭의 패널 목록. 데모는 `frontend/src/local/dart.ts`(v01-stella DART 대시보드 값: 사업보고서 2021~2025, `dart.duckdb` 분기 · 배당)와 `local/dashboard.ts`(패널 정의 · 계산). 실제 서비스는 DuckDB 적재 재무 수치를 `mcp-server`로 조회하고 비율 · 합계 · 증감 · 단위 환산은 코드로 계산한다(LLM에 맡기지 않음).

| 쿼리 | 설명 | 타입 |
|---|---|---|
| `company` | 기준 회사 (기본 `SKT`). 동종사 패널은 3사 | string? |
| `tab` | `dash` / `pl` / `bs` / `cf` / `capex` / `peer` / `insights` 중 하나. 없으면 전부 | string? |
| `period` | `y`(연간) / `q`(분기, 손익만) | string? |

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `layout` | 탭 → 패널 ID 순서 (`dash`, `fin.{pl,bs,cf,capex,peer}.{y,q}`, `insights`) | object |
| `panels[]` | 패널 (아래) | Panel[] |
| `source` | 출처와 조회 시각 | string |

`Panel`: `{ id, title, sub(기준: SKB 포함 · SKT 단독 · 각 사 자회사 포함 등), span(1~12 열), unit(억원 · % · 배 · 원 · 지수), chart, cap?, ai?, news? }`. `chart.type`은 `bars` · `lines` · `stack` · `waterfall` · `scatter` · `hbars` · `treemap` · `heat` · `table` · `kpis` · `asset`이고, 타입마다 화면이 그대로 그릴 숫자(`labels`, `series[{ name, v[] }]`, `steps`, `points`, `rows` 등)를 담는다. `ai` · `news`는 연간 인사이트 패널의 AI 해석 목업과 근거 뉴스 ID.

`asset` 패널은 자산 계정(SKT 별도 분기 장부금액)이다: `{ quarters[], accounts[{ group: t|i, key, name, values[], note? }], evidence{ "key|분기": { lv, n, unx, later } } }`. `evidence`(근거 칸 상태)는 데모에서 화면 설명용 예시이며, 실제 산출 방식은 ai와 정한다.

캔버스 대시보드 카드(`dash`)의 컨텍스트 · 근거는 패널의 표 형태(행 이름 × 열)에서 만든다. 근거 ID는 `dash:{panelId}#{인코딩된 행 이름}@{열 인덱스}`다. 정리 글과 근거 검토는 같은 숫자 열을 사용한다. 산점도는 영업이익률·부채비율·매출을 각각 인용하며, 근거 목록의 억원 값은 십억원으로 환산한다.

## 5. 문서 (IR·AR·뉴스·사내 자료)

### 5.1 `GET /api/v1/documents`

| 쿼리 | 설명 | 타입 |
|---|---|---|
| `kind` | `ir`, `ar`(애널리스트 리포트, 쟁점 요약만), `news`, `internal` 중 하나 이상 (쉼표로 구분) | string |
| `q` | 검색어. 회사가 나오면 그 회사와 3사 공통 자료만 | string? |
| `company` | 회사 필터 (`SKT`, `KT`, `LGU+`) | string? |
| `canvasId` | `q`가 없을 때 이 캔버스의 회사·개념과 겹치는 순으로 추천 | string? |

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `doc` | 문서 (아래) | Doc |
| `why` | 찾은·추천 이유 (`본문: 감가`, `회사 SKT · 개념 감가상각비`) | string |

`Doc`: `{ id, kind, company, title, publisher, date, summary, figures: [{ label, value, unit, period }], url, tags, uploaded, img? }`. `img`는 뉴스 썸네일(기사 og:image, 없으면 화면이 언론사 첫 글자로 대신). `date`는 발행일, `url`은 출처(사내 자료는 빈 값). IR 자료는 화면이 `회사 + 분기`로 줄여 보여 준다(`docTitle`).

`q`도 `canvasId`도 없으면 발행일 최신 순.

자료 검색 입력창은 탭과 무관한 통합 검색이다. 화면은 입력하는 즉시(약 150ms 묶음) 같은 `q`로 4.1(공시)과 이 API(`kind=ir,ar`, `kind=news`)를 함께 부른다. 임베딩 검색(F-04)이 들어오면 서버가 한 번에 돌려주는 `GET /search?q=`로 합칠 수 있다(backend·ai와 결정).

### 5.2 `GET /api/v1/documents/{id}`

문서 카드와 왼쪽 근거 목록에 쓰는 `Doc`을 반환한다. 업로드 문서는 해당 캔버스 권한자만 조회할 수 있다. 서버 모드에서는 캔버스를 다시 열거나 다른 편집자가 문서 카드를 추가할 때 ID로 조회한다.

### 5.3 `POST /api/v1/documents/uploads`

C16 데이터 입력 창에서 여러 파일을 고르면 파일마다 `multipart/form-data` 요청을 하나씩 보낸다.

| 필드 | 필수 | 값 |
|---|---|---|
| `file` | 예 | 파일 하나 |
| `canvasId` | 예 | 현재 캔버스의 숫자 ID |
| `company` | 예 | 현재 분석 대상 회사 (`analysis.focus`) |
| `publishedDate` | 아니요 | `yyyy-MM-dd`. 생략하면 업로드한 날 |

허용 확장자는 `pdf, xlsx, xlsm, csv, pptx, html, htm, xml, txt, md, json`이다.

`202` 응답은 `{ "data": { "jobId": 11, "documentIds": [21] } }` 형식이다. `GET /jobs/{jobId}`를 2초 간격으로 조회하고, `RUNNING`이 1분을 넘으면 5초 간격으로 조회한다. `SUCCEEDED`면 `GET /documents/{documentIds[0]}`로 카드 데이터를 받는다. 업로드 문서의 `kind`는 `internal`, `publisher`는 `사내 업로드`, `uploaded`는 `true`, `url`은 빈 문자열이다. `FAILED`면 해당 파일 줄에 `errorMessage`를 표시한다.

| HTTP | 코드 | 상황 |
|---|---|---|
| 400 | `C001` | 필수 값 누락, 빈 파일, 지원하지 않는 형식(메시지에 허용 목록 포함) |
| 401 | `A002` | 로그인 필요 |
| 403 | `K001` / `K002` | 캔버스 권한 없음 / 보기 권한만 있음. 소유자와 편집자만 업로드 가능 |
| 404 | `C002` | 캔버스 없음 |
| 413 | `C001` | 파일이 너무 큽니다 (최대 50MB). |

로컬 데모는 파일의 형식과 대상 회사를 검사하고, 읽을 수 있는 텍스트의 앞부분을 요약으로 사용한다.

## 6. 비교

### 6.1 `GET /api/v1/canvases/{id}/compares/{cardId}`

비교 카드와 그 시각화 카드가 쓴다. 개념 사전과 사용자가 확정한 대응(`confirm_mapping`)으로 3사 계정을 맞춘다. 원본 카드의 행·기간·수정값이 바뀌면 다시 부른다(화면은 이벤트 전송 뒤 `version`을 올려 다시 부름).

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `groups[]` | 열 순서 `{ company, basis }` | object[] |
| `lines[]` | 맞춘 계정 줄 | CompareLine[] |
| `unmatched[]` | 맞추지 못한 줄 | CompareLine[] |

공통 계정이 하나도 없으면(맞춘 줄 0개) 비교를 고집하지 않고, 고른 표의 행을 모두 `lines`에 `why: "표 그대로"`로 넣는다(거부한 대응 행만 `unmatched`에 남음). 화면은 비교 카드 제목을 `표 모음`, 시각화 카드 제목을 `계정별 값`으로 바꾸고 확정 칸을 숨긴다.
| `warnings[]` | 연결/별도·단위·기간 차이 안내. 화면은 카드에 목록으로 띄우지 않고 부제 한 줄과 분석 지적(7.2)에만 씀 | string[] |
| `rejected[]` | 사용자가 거부한 개념 이름 | string[] |

사용자 대응 반영: 확정(`confirm_mapping` `accepted: true`)한 줄은 `confirmed: true`. 거부한 개념은 `lines`에서 빼고, 그 행들을 회사별로 `unmatched`에 `why: "거부한 대응"`, `conceptId`(되돌리기용)와 함께 넣는다.

`CompareLine`: `{ label, why, conceptId, note, values: (Part[] | null)[], totals?: (number | null)[], confirmed? }`. `values`는 `groups` 순서이고, `Part`는 `{ cardId, cellId, label, valueBil }`(십억원). `cellId` 형식은 `{tableId}#r{행}c{열}`.

## 7. 분석 (검토 · 질문 · 평가)

캔버스 하단 `분석하기`(또는 C20 `새로 분석하기`)를 누르면 분석 세션을 하나 만든다. 세션은 검토(7.2) → 질문(7.4)을 한 작업으로 진행하고, 평가(7.5)는 다음 분기 자료가 들어온 뒤 세션에 붙인다. 새 세션은 `새로 분석하기`(또는 세션이 없을 때 `분석하기`)로만 만든다. 캔버스가 바뀐 뒤 `업데이트`를 누르면 보고 있는 세션의 새 버전을 만든다(새 분석 결과, `baseAnalysisId`로 같은 세션에 묶음). 결과는 고치지 않고 쌓으므로 이전 버전과 그 평가는 그대로 남는다. 세션은 요청한 본인만 조회하고, 지우기는 내 목록에서 숨길 뿐 결과는 남긴다(숨김 목록은 데모 `localStorage` `telescope-analyses-hidden:v1`). 숨길 수 있다. 기준 시점(`asOfDate`)은 페이지의 기준 시점이고 필수다.

### 7.1 `GET /api/v1/canvases/{id}/analyses`

내 분석 세션 목록(최신 먼저). C25 세션 목록과 C10 지적 박스(최신 세션)에 쓴다. 데모는 `localStorage` `telescope-analyses:v1`에 `{페이지ID}|{사번}`별로 저장한다.

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `id`, `createdAt` | 분석 결과 ID, 만든 시각 | string |
| `baseAnalysisId` | 업데이트로 만든 버전이면 처음 세션의 ID (없으면 처음 버전) | string? |
| `canvasSig` | 분석 당시 캔버스 내용 지문(좌표 제외). 지금 캔버스와 다르면 화면이 "캔버스가 변경되었습니다" | string |
| `cards`, `asOfDate` | 카드 수, 기준 시점 | number, string |
| `status` | `RUNNING` / `REVIEWED`(검토 · 질문 끝, 평가 대기) / `EVALUATED` / `FAILED` | string |
| `review` | 7.2 응답 | object |
| `questions[]`, `assumptions[]` | 7.4 응답 | object[] |
| `evaluation` | 7.5 응답 또는 `null` | object \| null |

`POST /api/v1/canvases/{id}/analyses/{analysisId}/hide`: 내 목록에서 그 세션(모든 버전) 숨기기, `204`. 결과는 지우지 않는다. 다른 사람 세션은 `404`. 데모는 `canvas.hideAnalysis`. 목록 응답에는 숨긴 세션을 넣지 않는다.

### 7.2 `POST /api/v1/canvases/{id}/analyses` (검토)

`{ asOfDate }`를 보내면 `202` + `{ jobId, analysisId }`. 업데이트는 같은 `POST`에 `{ asOfDate, baseAnalysisId }`를 보내 새 분석 결과를 만든다(기존 결과는 고치지 않음, 데모는 `Session.base`). job 진행 단계(`step: REVIEW` → `QUESTIONS`)로 프로세스 바를 움직인다. LLM에는 아래 컨텍스트 층(7.3)을 보내고, 응답의 근거 ID는 서버가 컨텍스트에 실제로 있는지 검증한 것만 남긴다. 데모는 규칙 기반(`local/ai.ts` `review`).

| `review` 필드 | 설명 | 타입 |
|---|---|---|
| `memos[]` | 메모 문장별 분류 `{ key, kind: 지시 \| 근거 \| 의견, text, where, author?, cardId?, linkId? }` | object[] |
| `claims[]` | 주장 `{ id, text, from, source, cardId?, tags[], company?, verdict: 충분 \| 보통 \| 부족, evidence: [{ cite, label, source }], need? }`. 주장 = 의견 메모 문장 + 문서 카드 요약 | object[] |
| `overall` | `{ verdict, text }` 전체 판정 | object |
| `findings[]` | 캔버스 박스로 표시할 지적 `{ cardId, level: bad \| warn, text, ref }`: 근거 부족, 연결 · 별도 섞임, 단위 환산, 사용자 수정값, 연결 없음 | object[] |
| `insights[]` | AI 인사이트 `{ id, title, facts: [{ text, cite? }], ai, news[] }`. `facts`의 숫자 · 변화율은 코드 계산, `ai`는 AI 해석 | object[] |
| `note` | 캔버스 정리 글(Markdown, 근거 ID 포함). 복사 · Markdown 내보내기용 | string |

판정 규칙(데모): 공시 숫자가 있고 출처가 2종 이상이면 `충분`, 숫자 근거만 있으면 `보통`, 없으면 `부족`. 근거는 주장과 같은 개념 태그 · 같은 회사의 7.6 근거 줄. 숫자는 코드로 계산하고 LLM이 새 숫자를 만들지 않는다.

### 7.3 컨텍스트 층 (서버 내부)

| 층 | 내용 |
|---|---|
| `L0_system` | 규칙(근거 ID, 메모는 지시 · 근거 · 의견으로 가림, 선 관계는 에이전트가 판단), 개념 사전 |
| `L1_sources` | 출처 (공시 접수번호, 문서 ID · 발행처 · 발행일) |
| `L2_workspace` | 카드 내용 (공시 표, 문서 요약 · 수치, 대시보드 값, 메모 카드) |
| `L3_edits` | 수정값, 확정 · 거부 대응, 비교 · 시각화 원본, 연결, `# 메모`(카드 메모 · 선 메모) |
| `L4_task` | 요청 |

근거 ID 형식: 표 셀 `[{tableId}#r{행}c{열}]`, 대시보드 값 `[dash:{panelId}#{인코딩된 행 이름}@{열 인덱스}]`, 문서 수치 `[doc:{문서ID}#f{번호}]`, 문서 내용 `[doc:{문서ID}]`. 앞 층이 같으면 LLM prefix 캐시를 재사용한다.

### 7.4 질문 (같은 분석 작업의 두 번째 단계)

`questions[]`: `{ topic, tags[], q, why, level, cite[] }`(`높음`이 먼저). `assumptions[]`: `{ tag, name, value, cite, period }`(평가에 쓸 가정 = 대상 회사 기준 분기 공시 원본값, 사용자 수정값 · 다른 기간 열 제외, 대상 계정은 `analysis.json` `deliverables[].assume` 합집합). 화면은 애널리스트 리포트 형식(요지 · 핵심 쟁점 · 쟁점별 절)으로 보여 주고 Markdown을 만든다.

### 7.5 `POST /api/v1/analyses/{id}/evaluations`

다음 분기 자료(실적 · 애널리스트 리포트)가 들어온 뒤 그 세션의 질문 · 가정을 평가한다. 데모는 C23 `다음 분기 자료 반영 (데모)` 버튼이 이 호출을 대신한다. 다음 분기 자료가 "들어왔다"를 무엇으로 판단할지(리포트 수집 시점, 관리자 등록)는 정하지 않았다.

| 응답 `data` (`evaluation`) | 설명 | 타입 |
|---|---|---|
| `target` | `{ label, date, company }` 평가 기준 분기 (예: 2Q26) | object |
| `hit[]`, `off[]`, `missed[]` | 맞은 질문(쟁점 링크) · 리포트에 없던 질문 · 놓친 쟁점 | object[] |
| `checks[]` | 가정 대조 `{ tag, name, value, actual, diff, ok, cite, period }`. 차이 5% 이내면 유지 | object[] |
| `precision`, `recall` | 정밀도·재현율 (%) | number |

| HTTP | 상황 | 화면 |
|---|---|---|
| 409 | 다음 분기 자료가 아직 없음 | C23 회색 대기 유지 |

### 7.6 `GET /api/v1/canvases/{id}/evidence`

캔버스 카드를 개념 태그가 붙은 근거 줄로 편다. 검토 · 질문 · 가정의 입력이며 서버 안에서 쓴다.

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `tag`, `company`, `label`, `period` | 개념, 회사, 계정·제목, 기간 | string |
| `value`, `unit` | 값. 공시는 십억원으로 변환. 문서 요약 줄은 `null` | number \| null, string |
| `cite`, `cardId` | 근거 ID, 카드 ID | string |
| `source` | `공시` / `IR` / `AR` / `뉴스` / `사내` | string |
| `text`, `edited` | 문서 요약, 사용자 수정값 여부 | string?, boolean |

### 7.7 `GET /api/v1/deliverables` (화면에서 쓰지 않음)

양식 정의 `{ focus, tagNames, deliverables[] }`. 화면은 양식을 고르지 않고, 서버가 `assume` 합집합(7.4)과 개념 이름에 쓴다.

## 8. 오류 처리 (화면)

| 상황 | 화면 동작 |
|---|---|
| 이벤트 전송 실패 (네트워크·5xx) | 같은 `requestId`로 다음에 다시 보냄. "서버에 연결하지 못했습니다. 연결되면 다시 보냅니다." |
| 글 충돌 (`409 K003`) | 충돌한 글을 최신으로 바꾸고 "다른 사람이 먼저 고쳤습니다. 최신 글로 바꿨으니 확인한 뒤 다시 고쳐 주세요." |
| 다른 사람 변경의 번호 누락, 재접속 | 캔버스를 다시 조회 |
| 권한 회수·캔버스 삭제 알림 | 캔버스 홈으로 가고 "이 캔버스를 볼 권한이 없습니다." 또는 "캔버스가 삭제되었습니다." |
| 비교 조회 실패 | 이전 결과를 그대로 보여 줌 |
| 카드 태그 조회 실패 | 이전 태그를 유지 |
| 401 | 로그인 화면으로 |
| job `FAILED` | 프로세스 바의 그 단계에 `errorMessage`를 표시하고 `새로 분석하기` 유지 |
| 업로드 검증 실패 | C16 파일 줄에 `포맷 불일치` · `회사 불일치`와 서버 `message` |

## 9. 정해야 할 것

- 공유 알림 (초대받은 사람에게 알릴지, 알린다면 메일 · 사내 메신저 중 무엇으로)
- 소유권 넘기기, 소유자 퇴사 · 부서 이동 시 처리
- 공유 링크 복사 기능 여부
- 분석 작업 결과를 받는 경로 (`GET /jobs/{id}`에 `analysisId`를 넣을지, 7.1 목록을 다시 부를지)
- 다음 분기 자료가 "들어왔다"를 판단하는 방법과 평가를 자동으로 돌릴지
- 분석 세션을 협업자에게 공유하는 방법 (지금은 본인만)
- 메모 분류 · 주장 판정을 LLM으로 바꿀 때 판정 기준과 검증
- 통합 검색을 서버 한 API(`GET /search`)로 합칠지, 화면이 공시 · 문서 검색을 나눠 부를지
- 업로드 허용 형식과 업로드 문서 정책 (`CLAUDE.md` 7장)
- 채팅 응답 방식 (`CLAUDE.md` 7장). 데모에 채팅 화면이 없어 이 문서에 넣지 않음
