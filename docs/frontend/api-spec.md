# 프런트엔드 API 명세 (초안)

v02-canvas 데모(코드 `frontend/`, 빌드 `docs/frontend/demo.html`)가 화면에서 부르는 기능을 서버 API로 옮겨 적은 문서다. 데모는 `frontend/src/api.ts`의 `api` 객체가 브라우저 안 구현(`local/server.ts`, `local/ai.ts`)을 부른다. 실제 서비스에서는 같은 자리에서 아래 API를 부른다.

- 근거 코드: `frontend/src/api.ts`(타입), `stores/canvas.ts`(캔버스·이벤트), `local/server.ts`(검색·비교·컨텍스트), `local/ai.ts`(정리 글·질문·평가), `local/dashboard.ts`·`local/dart.ts`(대시보드 패널)
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
| 권한 | 캔버스마다 소유자 · 편집 · 보기 권한(3.9). 권한 없는 캔버스는 403. 공용 문서 수집·삭제는 관리자만 |

### 1.1 화면별 호출 요약

| 화면 | 기능 | API | 데모 함수 |
|---|---|---|---|
| 로그인 | 로그인 | `POST /auth/login` | `canvas.login` |
| 로그인 | 내 정보 | `GET /users/me` | `restoreUser` |
| 캔버스 홈 (C00) | 볼 수 있는 캔버스 목록·삭제 | `GET /canvases`, `DELETE /canvases/{id}` | `canvases`, `removeCanvas`, `thumb` |
| 새 캔버스 (C14) | 이름 · 분기 · 편집할 사람으로 생성, 사람 찾기 | `POST /canvases`, `GET /users?q=` | `newCanvas`, `findPeople` |
| 상단 바 | 캔버스 이름 변경 | `PATCH /canvases/{id}` | `rename` |
| 메뉴 팝업 (C15) | 나의 캔버스 목록 | `GET /canvases` | `canvases`, `canvas.open` |
| 공유 창 (C13) | 사내 사람 찾기 | `GET /users?q=` | `ALL_MEMBERS` 상수 |
| 공유 창 (C13) | 권한 조회·변경 | `GET/PUT /canvases/{id}/shares` | `members`, `setShare` |
| 캔버스 | 캔버스 열기 | `GET /canvases/{id}` | `canvas.open` |
| 캔버스 | 카드·연결·메모·프롬프트 조작 | `POST /canvases/{id}/events` | `api.postEvents` |
| 왼쪽 사이드바 | 프롬프트 목록 | (캔버스 상태에서 계산, API 없음) | `cardTitle`, `userPrompts` |
| 캔버스 | 같은 개념 자동 연결 | `GET /canvases/{id}/card-tags` | `api.cardTags` |
| 자료 검색 · 대시보드 · 회사 재무 탭 | 대시보드 패널 (KPI · 차트 · 표 · 자산 계정) | `GET /dashboard` | `local/dashboard.ts` `panelOf`, `LAYOUT` |
| 자료 검색 · 통합 검색 | 공시 재무 데이터 검색 (입력 즉시, 아래 두 줄과 같은 `q`로 함께 부름) | `GET /filings/search` | `api.searchQuestion` |
| 자료 검색 · 통합 검색 | IR·AR · 뉴스 검색 | `GET /documents?kind=ir,ar`, `GET /documents?kind=news` | `api.searchDocs` |
| 자료 검색 · 행 고르기 / 원본 보기 | 공시 표 조회 | `GET /filings/tables/{tableId}` | `api.table`, `api.noteTable` |
| 자료 검색 · 인사이트 | 3사 비교 인사이트(분기) | `GET /insights` | `api.analysis.insights`, `api.peerFacts` |
| 자료 검색 · 인사이트 | 연간 인사이트 | `GET /dashboard` (인사이트 패널) | `local/dart.ts` `ANNUAL_INSIGHTS` |
| 자료 검색 · 인사이트 | 관련 뉴스 추천 (썸네일) | `GET /documents?kind=news` | `api.recommend` |
| 자료 검색 · 입력창 `+` | 사내 자료 업로드 | `POST /documents/uploads` | `api.registerDoc` |
| 비교·시각화 카드 | 표 비교 결과 | `GET /canvases/{id}/compares/{cardId}` | `api.compare` |
| 생성하기 → 정리 글 | 캔버스를 컨텍스트 글로 정리 | `POST /canvases/{id}/context-notes` → job | `ai.contextNote` (+ `userPrompts`, `memos`) |
| 생성하기 | 근거 목록 (질문 · 가정 입력) | `GET /canvases/{id}/evidence` | `api.evidence` |
| 생성하기 → 질문 | 질문 생성 | `POST /canvases/{id}/questions` → job | `ai.questions` |
| 생성하기 → 평가 | 평가 | `POST /canvases/{id}/evaluations` | `ai.evaluate` |
| 정리 글 · 질문 | 복사 · Markdown 내보내기, 정리 글 편집 | (화면에서 만듦, API 없음) | clipboard, Blob 내려받기 |

데모에만 있는 기능은 API를 두지 않는다: 로그인 화면의 데모 계정 미리 입력, `예시 불러오기`(`api.meta.example`), 시각화 카드(화면 전용, 컨텍스트 제외), 홈 미리보기(`thumb`, 실제 서비스는 서버가 만든 썸네일 또는 카드 좌표), `api.warmup`·`api.ask`(LLM 없음).

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

내가 볼 수 있는 캔버스 목록(캔버스 홈 C00). 소유한 것, 초대받은 것, 일반 액세스(부서 · 회사 전체)로 열리는 것을 모두 담는다. 최근 수정 순.

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
| `access` | 일반 액세스 `invited` / `company`(회사 전체 보기) | string |
| `quarter` | 분기 (예: `2026년 1분기`). 상단 제목 옆에 표시 | string |
| `thumbnail` | 홈 미리보기. 카드 `{ kind, x, y }` 목록 또는 이미지 URL (정해야 함) | object |

### 3.2 `POST /api/v1/canvases`

| 요청 | 설명 | 타입 |
|---|---|---|
| `name` | 이름. 없으면 `"{부서} 캔버스 {n}"` | string? |
| `quarter` | 분기 (예: `2026년 1분기`) | string |
| `editorIds` | 편집 권한을 줄 사번 목록 (C14 편집할 사람) | string[] |

응답은 3.1의 항목 하나. `201`. 만든 사람이 소유자가 되고, `editorIds`는 `edit`으로 초대되며, 일반 액세스는 `invited`(초대한 사람만)로 시작한다.

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
| `links` | `{ id, source, target, rel }` 목록 | Link[] |
| `lastSeq` | 마지막 이벤트 번호 | number |

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 권한 없음(공유가 풀림 등) | "이 캔버스를 볼 권한이 없습니다." 후 캔버스 홈 |
| 404 | 없는 캔버스 | — |
| 409 | 카드가 가리키는 표·문서가 사라짐 | "'{이름}' 캔버스 일부를 복원하지 못했습니다. 자료가 바뀌었을 수 있습니다." |

### 3.5 카드·연결 타입

좌표(`x`, `y`)는 화면 배치용이며 컨텍스트에 들어가지 않는다.

| `kind` | 필드 | 설명 |
|---|---|---|
| `table` | `tableId`, `rows`, `cols`, `vcol`, `edits`, `sort` | 공시 표 카드. `vcol`은 비교 기준 열, `edits`는 `"r,c": 값` |
| `compare` | `sources` | 표 비교 카드. 원본 표 카드 ID 목록 |
| `doc` | `docId` | IR·AR·뉴스·사내 자료 카드 |
| `memo` | `author`, `text`, `type` | 팀원 메모 카드. `type`: `memo`(메모) / `request`(보완 요청). 컨텍스트에 의견으로 들어가고 사실 근거로 쓰지 않음 |
| `dash` | `panel` | 대시보드 카드. `panel`은 4.4 패널 ID (예: `kpis`, `op_bridge`, `yoy_heat`, `asset`, `pl_rev`, `capex`, `opi`, `peer`, `ins_op`) |
| `chart` | `compareId` | 시각화 카드 (화면 전용) |

메모 카드를 뺀 모든 카드는 `prompt`(카드 프롬프트, 이 근거를 어떻게 활용할지)를 가질 수 있다. 프롬프트만 담는 카드는 없다.

연결 `rel`: `근거` / `설명` / `반박` / `관련`. 연결은 `prompt`(선 프롬프트, 이 관계를 어떻게 쓸지)를 가질 수 있다.

### 3.6 `POST /api/v1/canvases/{id}/events`

카드·연결 조작을 묶어 보낸다. 화면은 150ms 동안 모은 이벤트를 한 번에 보낸다. 실패하면 화면이 같은 묶음을 다음에 다시 보낸다.

| 요청 | 설명 | 타입 |
|---|---|---|
| `events` | 이벤트 목록. 작성자는 토큰의 사용자 | Event[] |

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 보기 권한 | "보기 권한만 있습니다." (화면은 보기 권한이면 편집 도구를 숨기고 보내지 않음) |

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `lastSeq` | 반영 후 마지막 이벤트 번호 | number |
| `firstChangedLayer` | 처음 바뀐 컨텍스트 층 (`L1_sources` / `L2_workspace` / `L3_edits` / `null`) | string \| null |

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
| `add_memo` | `cardId`, `text`, `author`, `memoType` | 메모를 남김. `memoType`: `memo` / `request` |
| `edit_memo` | `cardId`, `text` | 메모를 고침 |
| `set_memo_type` | `cardId`, `memoType` | 메모 종류를 바꿈 |
| `set_card_prompt` | `cardId`, `text` | 카드 프롬프트를 고침. 빈 값이면 지움 |
| `set_link_prompt` | `linkId`, `text` | 선 프롬프트를 고침. 빈 값이면 지움 |
| `create_compare` | `cardId`, `sources` | 비교 카드를 만듦 |
| `confirm_mapping` | `conceptId`, `company`, `cells`, `accepted` | 계정 대응을 확정함 |
| `add_link` | `linkId`, `source`, `target`, `rel` | 카드를 연결함 |
| `set_link` | `linkId`, `rel` | 연결 관계를 바꿈 |
| `remove_link` | `linkId` | 연결을 지움 |
| `remove_card` | `cardId` | 카드를 지움. 서버가 그 카드의 연결과 비교 원본에서도 뺌 |
| `move_card` | `cardId`, `x`, `y` | 좌표만 저장. 컨텍스트·활동 기록에 영향 없음 |

#97 중간 저장본의 프롬프트 카드(`kind: prompt`, 이벤트 `add_prompt` · `edit_prompt`)는 메모 카드(`memo`, `add_memo` · `edit_memo`)로 읽는다.

```json
{ "events": [
  { "type": "add_card", "cardId": "c1", "tableId": "SKT_2026Q1_is", "rows": [3, 5], "cols": [0, 1] },
  { "type": "add_link", "linkId": "l2", "source": "c1", "target": "c3", "rel": "근거" }
] }
```

카드·연결 ID(`c{n}`, `l{n}`)는 데모처럼 화면이 만든다. 여러 사람이 동시에 고칠 때 ID가 겹치지 않게 하는 방식은 backend와 정한다.

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

공유 창(C13). `GET`은 권한 있는 사람 전원(활동 로그 "이 캔버스 사람"에도 씀), `PUT`은 소유자 · 편집 권한만 부른다.

| 필드 | 설명 | 타입 |
|---|---|---|
| `ownerId` | 소유자 사번 (`GET`만, 바꿀 수 없음) | string |
| `members` | 직접 초대한 사람 `{ userId, role }`. `role`은 `edit` / `view`. 목록에서 빠지면 권한 삭제 | object[] |
| `access` | 일반 액세스 `invited`(초대한 사람만) / `company`(회사 전체, 보기 권한 고정). 편집 권한은 `members`로만 줌 | string |

`GET` 응답에는 일반 액세스로 권한을 받은 사람까지 펼친 `people[]`(`{ id, name, dept, job, role }`)를 함께 준다.

```json
{ "members": [{ "userId": "2001", "role": "view" }], "access": "invited" }
```

권한 계산 순서: 소유자 → 직접 초대 권한 → 일반 액세스. 바꾼 결과는 활동 로그에 "공유 권한을 바꿈"으로 남는다.

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 403 | 보기 권한으로 `PUT` | 화면은 공유 창을 읽기 전용으로 보여 줌 ("보기 권한이라 공유 설정을 바꿀 수 없습니다.") |
| 404 | 없는 사번 | "찾는 사람이 없거나 이미 권한이 있습니다." |

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

캔버스 대시보드 카드(`dash`)의 컨텍스트 · 근거는 패널의 표 형태(행 이름 × 열)에서 만든다. 근거 ID는 `dash:{panelId}#{행 이름}`, 값은 억원을 십억원으로 환산한 마지막 열.

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

문서 하나. 문서 카드, 왼쪽 사이드바 근거 데이터가 쓴다. 응답은 `Doc`.

### 5.3 `POST /api/v1/documents/uploads`

`사내 자료` 탭에 파일을 올린다. `multipart/form-data`, 필드 `file`(여러 개 가능). 데모는 txt·md·csv만 읽어 브라우저에서 요약을 만든다. 서버는 파서 모델로 요약·수치·개념 태그를 뽑으므로 자료 준비 작업으로 돌린다.

| 응답 | 설명 |
|---|---|
| `202` + `{ jobId, documentIds }` | `GET /jobs/{id}`가 `SUCCEEDED`가 되면 `GET /documents/{id}`로 요약본을 받아 카드로 올림 |

| HTTP | 상황 | 화면 문구 |
|---|---|---|
| 400 | 지원하지 않는 형식 | "파일을 읽지 못했습니다: {사유}" |

허용 파일 형식, 업로드 문서의 소유자·만료·접근 범위는 미정(`CLAUDE.md` 7장)이다.

## 6. 비교

### 6.1 `GET /api/v1/canvases/{id}/compares/{cardId}`

비교 카드와 그 시각화 카드가 쓴다. 개념 사전과 사용자가 확정한 대응(`confirm_mapping`)으로 3사 계정을 맞춘다. 원본 카드의 행·기간·수정값이 바뀌면 다시 부른다(화면은 이벤트 전송 뒤 `version`을 올려 다시 부름).

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `groups[]` | 열 순서 `{ company, basis }` | object[] |
| `lines[]` | 맞춘 계정 줄 | CompareLine[] |
| `unmatched[]` | 맞추지 못한 줄 | CompareLine[] |
| `warnings[]` | 연결/별도·기간 차이 등 안내 | string[] |

`CompareLine`: `{ label, why, conceptId, note, values: (Part[] | null)[], totals?: (number | null)[] }`. `values`는 `groups` 순서이고, `Part`는 `{ cardId, cellId, label, valueBil }`(십억원). `cellId` 형식은 `{tableId}#r{행}c{열}`.

## 7. 캔버스 정리 · 질문 · 평가 (오른쪽 결과 패널)

상단 `생성하기` 한 번으로 7.4(정리 글) → 7.3 · 7.5(질문) → 7.6(평가)을 차례로 부르고, 프로세스 바에 단계별 진행을 표시한다. 생성하기 첫 단계는 결과물(예산서 · 사업기획서)을 만들지 않고, 캔버스 내용을 글로 정리한 컨텍스트를 만든다. 근거 부족 판정은 하지 않는다.

### 7.1 `GET /api/v1/deliverables` (화면에서 쓰지 않음)

#97 추가 수정에서 결과 패널의 `결과물` 선택을 없앴다. 화면은 양식을 고르지 않는다. 평가(7.6)의 가정 계정은 양식들의 `assume` 태그 합집합을 서버가 쓴다. 개념 이름(`tagNames`)은 문서 카드 태그와 같은 개념 점선에 계속 쓴다.

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `focus` | 주 대상 회사 | string |
| `tagNames` | 개념 ID → 이름 | Record |
| `deliverables[]` | `{ id, name, goal, sections: [{ title, tags }], assume }` | object[] |

### 7.2 `POST /api/v1/canvases/{id}/context`

LLM에 보내는 층별 컨텍스트. 화면에서 직접 부르지 않고(`프롬프트 복사` 삭제), 정리 글(7.4)이 서버 안에서 같은 층을 쓴다. 캔버스의 카드·연결·수정 이력·메모·사용자 프롬프트를 층으로 묶는다.

| 요청 | 설명 | 타입 |
|---|---|---|
| `extra` | 추가 요청 (선택) | string? |

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `layers` | `L0_system`(규칙·개념 사전), `L1_sources`(출처), `L2_workspace`(카드·대시보드·메모), `L3_edits`(수정·대응·비교·연결 + `# 사용자 프롬프트`: 카드 프롬프트, 선 프롬프트), `L4_task`(요청) | Record |
| `hashes` | 층별 해시. 앞 층이 같으면 LLM prefix 캐시 재사용 | Record |
| `messages[]` | `{ role, content }`. L0~L2는 system, L3~L4는 user | object[] |

근거 ID 형식: 표 셀 `[{tableId}#r{행}c{열}]`, 대시보드 값 `[dash:{panelId}#{행 이름}]`, 문서 수치 `[doc:{문서ID}#f{번호}]`, 문서 내용 `[doc:{문서ID}]`. 메모는 팀원 의견이라 근거 ID를 붙이지 않는다.

### 7.3 `GET /api/v1/canvases/{id}/evidence`

캔버스 카드를 개념 태그가 붙은 근거 줄로 편다. 질문 · 가정의 입력이며, 화면은 근거 ID를 눌러 카드를 찾을 때 쓴다.

| 응답 `data[]` | 설명 | 타입 |
|---|---|---|
| `tag`, `company`, `label`, `period` | 개념, 회사, 계정·제목, 기간 | string |
| `value`, `unit` | 값. 공시는 십억원으로 변환. 문서 요약 줄은 `null` | number \| null, string |
| `cite` | 근거 ID | string |
| `cardId` | 카드 ID | string |
| `source` | `공시` / `IR` / `AR` / `뉴스` / `사내` (대시보드 값은 `공시`) | string |
| `text` | 문서 요약 (문서 줄만) | string? |
| `edited` | 사용자가 고친 값이면 `true` | boolean |

### 7.4 `POST /api/v1/canvases/{id}/context-notes`

생성하기의 첫 단계. 캔버스의 카드 · 연결 · 카드/선 프롬프트 · 메모를 사람이 읽는 글(Markdown)로 정리한다. 다음 작업(질문, 이후 문서 작성)의 컨텍스트로 쓴다. LLM을 부르므로 작업으로 돌린다(job `ANALYSIS`). 데모는 규칙 기반(`local/ai.ts` `contextNote`)으로 같은 모양을 만든다.

| 요청 | 설명 | 타입 |
|---|---|---|
| `asOfDate` | 기준 시점. 이 날짜 이전 자료만 씀 | string |

`202` + `{ jobId }` → 완료 뒤 `GET /api/v1/context-notes/{noteId}`(`noteId`는 작업 결과에서 받음, 받는 방식은 backend와 합의).

| `GET /context-notes/{noteId}` 응답 `data` | 설명 | 타입 |
|---|---|---|
| `id`, `createdAt` | 정리 글 ID, 만든 시각 | string |
| `markdown` | 정리 글. `# 제목`, 기준 분기 · 카드 수, `## 섹션`(공시 재무 · DART 대시보드 · 계정 대응 비교 · IR·AR · 뉴스 · 사내 자료 · 카드 사이 관계 · 팀 메모 · 보완 요청) 아래 `- 줄`. 숫자 줄 끝에 `[근거ID]`, 카드 프롬프트는 그 카드 줄 아래 `- 지시: …` | string |
| `cites[]` | 글에 쓴 근거 ID 목록 (서버가 컨텍스트에 실제로 있는지 검증한 것만) | string[] |

규칙: 숫자는 카드 값을 코드로 옮기고(단위 환산 포함) LLM이 새 숫자를 만들지 않는다. 사용자 수정값은 "사용자 수정값"으로 적는다. 메모와 AI 해석은 사실 근거가 아님을 글에 밝힌다. 화면은 글을 고칠 수 있고(편집), `복사` · `Markdown` 내보내기는 화면이 만든다. 고친 글을 서버에 저장할지는 정하지 않았다(데모는 패널 안에서만 유지). 결과는 고치지 않고 새로 쌓는다.

### 7.5 `POST /api/v1/canvases/{id}/questions`

생성하기의 두 번째 단계. job `PREDICTION`. 요청은 `{ asOfDate, contextNoteId? }`. 데모는 7.3 근거 줄의 개념 태그로 질문 뱅크에서 고른다. Markdown 파일은 화면이 만든다.

완료 뒤 `GET /api/v1/question-sets/{id}`:

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `id`, `createdAt` | 질문 묶음 ID, 만든 시각 | string |
| `questions[]` | `{ topic, q, why, level, cite[] }`. `level`: `높음` / `보통` 등, `높음`이 먼저 | object[] |

### 7.6 `POST /api/v1/canvases/{id}/evaluations`

생성하기의 세 번째 단계. 질문은 다음 분기 리포트 쟁점과 같은 주제면 적중, 가정(캔버스의 대상 회사 기준 분기 공시 원본값이 다음 분기에도 유지된다고 둠, 사용자 수정값 · 다른 기간 열 제외)은 실제 실적과 차이 5% 이내면 유지로 판정한다. 다음 분기 자료는 평가에서만 쓴다.

| 요청 | 설명 | 타입 |
|---|---|---|
| `questionSetId` | 평가할 질문 묶음 | string |
| `canvasId` | 가정을 뽑을 캔버스 (서버가 7.3 근거에서 계산) | string |

| 응답 `data` | 설명 | 타입 |
|---|---|---|
| `target` | `{ label, date, company }` 평가 기준 분기 (예: 2Q26) | object |
| `hit[]` | 맞은 질문 `{ q, issue: { id, topic, title, publisher, url } }` | object[] |
| `off[]` | 리포트 쟁점에 없던 질문 | object[] |
| `missed[]` | 놓친 쟁점 | object[] |
| `checks[]` | 가정 대조 `{ tag, name, value, actual, diff, ok, cite, period }`. `diff`는 % | object[] |
| `precision`, `recall` | 정밀도·재현율 (%) | number |

평가를 동기로 돌려줄지 작업으로 돌릴지는 backend와 정한다(데모는 즉시 계산).

## 8. 오류 처리 (화면)

| 상황 | 화면 동작 |
|---|---|
| 이벤트 전송 실패 | 묶음을 큐 앞에 되돌리고 "조작을 반영하지 못했습니다. 새로고침 후 다시 시도하세요." |
| 비교 조회 실패 | 이전 결과를 그대로 보여 줌 |
| 카드 태그 조회 실패 | 이전 태그를 유지 |
| 401 | 로그인 화면으로 |
| job `FAILED` | 프로세스 바의 그 단계에 `errorMessage`를 표시하고 `다시 생성` 유지 |

## 9. 정해야 할 것

- 공유 알림 (초대받은 사람에게 알릴지, 알린다면 메일 · 사내 메신저 중 무엇으로)
- 소유권 넘기기, 소유자 퇴사 · 부서 이동 시 처리
- 공유 링크 복사 기능 여부
- 동시 편집 시 카드·연결 ID 발급과 충돌 처리 (서버 발급 vs 화면 발급)
- 다른 팀원의 조작을 화면에 반영하는 방식 (폴링 주기, `GET /canvases/{id}/events?after={seq}` 여부)
- 정리 글·질문 작업 결과 ID를 받는 경로 (`GET /jobs/{id}` 응답에 넣을지, 캔버스별 최신 결과 조회로 할지)
- 평가의 동기·비동기 여부
- 생성하기 세 단계를 한 작업으로 묶을지, 단계별 작업 세 개로 둘지 (프로세스 바 진행 표시 방식)
- 화면에서 고친 정리 글을 서버에 새 버전으로 저장할지, 질문 생성 입력으로 다시 쓸지
- 통합 검색을 서버 한 API(`GET /search`)로 합칠지, 화면이 공시 · 문서 검색을 나눠 부를지
- 업로드 허용 형식과 업로드 문서 정책 (`CLAUDE.md` 7장)
- 채팅 응답 방식 (`CLAUDE.md` 7장). 데모에 채팅 화면이 없어 이 문서에 넣지 않음
