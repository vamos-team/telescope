# backend ↔ ai 호출 공통 규약

> 작성자 : 윤혜정 (이슈 #51)

> **상태: 초안.** backend 쪽에서 기존 코드(`job` 도메인, `PythonAiClient` 가칭 초안)와
> `docs/erd/entities.md`를 바탕으로 정리한 제안이다. ai 담당자의 확인을 거쳐야 이슈 #51의
> 체크리스트를 완료 처리할 수 있다. 항목별로 "제안"과 "열림(합의 필요)"을 구분해 표시했다.
>
> 이 문서는 **공통 규약**만 다룬다. 작업별(자료 준비/분석/예측/평가/재무 데이터 적재) 구체적인
> 요청·응답 필드는 각 작업의 입출력 이슈에서 ai 담당이 정한다. 여기서 쓰는 요청 예시는
> 공통 규약을 설명하기 위한 것이고, 필드 확정은 해당 이슈를 따른다.
>
> 전제: backend(HTTP 껍데기, job 관리)와 ai(구체적 API, 파이프라인)는 역할이 다르다.
> 분석·예측·ML 로직은 backend에 두지 않는다(`backend/CLAUDE.md` 3번). ai는 결과를 backend가
> 저장할 수 있는 형태로 돌려주기만 한다. 결과 저장은 backend만 한다(루트 `CLAUDE.md` 3번).

## 1. 전체 그림

```
backend                                   ai
  │                                        │
  │  Job.create (QUEUED, 트랜잭션 1)        │
  │                                        │
  │  POST /internal/v1/jobs/{jobType}      │
  │ ─────────────────────────────────────▶ │  (허용 문서·기준 시점은 backend가 확정해 전달)
  │ ◀───────────────────────────────────── │
  │   202 { jobId }                        │  jobId는 ai가 발급 (string)
  │                                        │
  │  성공 → markStarted                     │  ai는 실제 작업을 백그라운드로 시작
  │  (externalJobId=jobId, RUNNING)        │
  │  실패 → markFailed      (트랜잭션 2)     │
  │                                        │
  │  GET /internal/v1/jobs/{jobId}  ───────▶│  (클라이언트의 폴링마다, 종료 전까지 반복)
  │ ◀───────────────────────────────────── │
  │   200 { jobId, status, result,         │
  │         errorMessage }                 │
  │                                        │
  │  status == SUCCEEDED/FAILED            │
  │  → Job 갱신 + 대상 도메인이 result 저장 │
```

- `Job.create`가 **ai 호출보다 먼저**다(`JobService` 3단계 계약: `job/service/JobService.java` 상단
  문서, `job.md` 3.3 "다른 도메인이 작업을 시작하는 순서"). ai 시작 요청이 성공한 **뒤에**
  `Job`을 만들면, ai 작업은 이미 시작됐는데 그 직후 `Job` 저장이 실패할 경우 그 작업을 가리킬
  `jobId`가 어디에도 안 남아 추적할 수 없다. 그래서 반드시 QUEUED `Job`을 먼저 커밋하고,
  ai 호출은 트랜잭션 밖에서 하고, 성공/실패에 따라 `markStarted`/`markFailed`로 마무리한다.
- backend가 노출하는 `GET /api/v1/jobs/{id}`(클라이언트용, `docs/backend/job.md`)와 이 문서의
  `GET /internal/v1/jobs/{jobId}`(ai 호출용)는 **다른 API**다. 전자는 `Job.id`(숫자, PK)를 쓰고,
  후자는 ai가 발급한 `externalJobId`(문자열)를 쓴다. 섞어 쓰지 않는다.
- ai 쪽 엔드포인트의 기반 경로(`/internal/v1`)는 제안이다. backend의 공개 API(`/api/v1`)와
  헷갈리지 않도록 "내부 호출"임을 이름에 드러내자는 의도이며, ai 담당자가 FastAPI 껍데기를
  만들 때 원하는 경로로 바꿔도 된다. **열림.**

## 2. 결정 사항

### 2.1 jobId 형식과 발급 방식 — 제안

- jobId는 ai가 발급하는 **문자열**이다. 형식은 ai가 정하되, UUID v4를 권장한다
  (`PythonAiClientStub`이 이미 `UUID.randomUUID().toString()`을 쓴다).
- backend는 이 값을 그대로 `Job.externalJobId`(문자열 컬럼)에 저장한다. 의미를 해석하지 않고
  "같다/다르다"만 비교하므로, ai 쪽 형식이 바뀌어도 backend 코드는 영향받지 않는다.
- 클라이언트에게 노출되는 jobId는 이것과 **다르다**(1번 그림 참고). backend가 자체적으로
  발급하는 `Job.id`(bigint, PK)가 클라이언트용 jobId다. ai의 jobId는 backend 내부에서만 쓴다.
- **최대 길이 100자.** `Job.externalJobId` 컬럼이 `VARCHAR(100)`으로 이미 만들어져 있다
  (`V3__create_job.sql`). ai가 100자를 넘는 값을 돌려주면 `markStarted`가 그 값을 저장하지
  못해 막 시작한 작업의 jobId를 잃는다. UUID v4(36자)는 여유가 있지만, ai가 다른 형식을
  쓰기로 하면 이 상한을 반드시 지켜야 한다. 더 긴 값이 꼭 필요하면 컬럼 길이를 늘리는
  새 마이그레이션을 함께 만든다.

### 2.2 작업 상태 값과 전이 — 제안

- ai는 `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` 네 값만 돌려준다. 이미
  `AiJobStatus`(backend 쪽 변환용 enum)와 일치한다. 새 상태 값을 추가하지 않는다(필요하면
  2.3의 `progress`로 표현).
- 상태는 전이 규칙(`QUEUED → RUNNING → SUCCEEDED`, 또는 언제든 `FAILED`)을 따른다고
  **가정하지 않는다.** backend의 `Job.syncFrom`은 같은 상태 반복, 단계 건너뜀(`QUEUED`에서
  바로 `SUCCEEDED`)을 정상으로 보고 흡수한다(`docs/backend/job.md` 3.2). ai는 폴링 타이밍에
  따라 중간 상태를 못 보여줄 수 있다는 뜻이며, 이를 피하려고 애쓰지 않아도 된다.
- `FAILED`일 때는 `errorMessage`를 반드시 채운다. 2.5의 범위 규칙을 따른다.
- `SUCCEEDED`일 때는 `result`를 반드시 채운다. `result`의 모양은 작업별 이슈에서 정한다
  (이 문서에서는 다루지 않는다).

### 2.3 단계 표시(`step`, `progress`) — 제안: MVP에서 제외

- MVP에서는 두지 않는다. 클라이언트는 `QUEUED/RUNNING/SUCCEEDED/FAILED`만 보고 폴링하며,
  `job.md`도 진행률 표시를 요구하지 않는다. 필드를 추가하면 backend의 `Job` 엔티티·테이블에도
  컬럼이 늘어 두 쪽을 함께 바꿔야 한다.
- 나중에 필요해지면(사용자 체감 대기 시간이 길어지는 작업이 생기면) `progress`(0~100 정수)
  하나만 추가하는 쪽을 권장한다. `step`(문자열 단계명)은 ai 파이프라인 내부 구조를 노출하는
  것이라 API 계약에는 넣지 않는다.

### 2.4 호출 형태 (경로, 방식, 응답 코드) — 제안, ai 담당자 최종 확인 필요

| 호출 | 메서드/경로 | 성공 응답 | 용도 |
|---|---|---|---|
| 자료 준비 시작 | `POST /internal/v1/jobs/document-preparation` | `202` + `{ "jobId": "..." }` | `PythonAiClient.startDocumentPreparation` |
| 분석 시작 | `POST /internal/v1/jobs/analysis` | `202` + `{ "jobId": "..." }` | `PythonAiClient.startAnalysis` |
| 예측 시작 | `POST /internal/v1/jobs/prediction` | `202` + `{ "jobId": "..." }` | `PythonAiClient.startPrediction` |
| 상태 조회 | `GET /internal/v1/jobs/{jobId}` | `200` + `{ "jobId", "status", "result", "errorMessage" }` | `PythonAiClient.getJobStatus`. 폴링마다 반복 호출됨 |

- 작업 종류별로 경로를 나눈 이유: 각 작업의 요청 바디(`AnalysisRequest`, `PredictionRequest`,
  `DocumentPreparationRequest`)가 서로 다르다. 하나의 경로(`POST /jobs`)에 `jobType`을 바디로
  받는 방식도 가능하지만, 요청 스키마가 작업마다 달라 OpenAPI 문서가 더 단순해지는 쪽(경로
  분리)을 제안한다. **열림 — ai 담당자가 FastAPI 라우팅 선호를 밝히면 그대로 맞춘다.**
- 응답 바디는 backend의 `ApiResponse`(`data`/`error` 포장) 형식을 **따르지 않는다.** 그 포장은
  backend가 "클라이언트"에게 보내는 응답 전용 규약이고, ai는 backend의 내부 호출 상대일
  뿐이라 `PythonAiClient`의 DTO(`JobStartResponse`, `JobStatusResponse`) 필드와 1:1로 맞는
  평평한 JSON이면 된다.
- 재무 데이터 적재(`DataSyncRun`)와 예측 평가(`PredictionEvaluation`)는 `Job` 테이블을 쓰지
  않지만(`docs/erd/entities.md` 3.3, 3.6), "시작 → id 발급 → 상태 조회" 패턴 자체는 이
  문서의 공통 규약을 그대로 따른다고 제안한다. 다만 평가가 폴링이 필요할 만큼 오래 걸리는
  작업인지조차 정해지지 않았다 — **작업별 이슈(prediction)에서 결정.**

### 2.5 오류 응답 규약

**없는 작업(404)과 일시 오류 구분 — 제안, 기존 코드와 일치**

- 모르는 `jobId`로 상태를 조회하면 ai는 HTTP `404`를 반환한다. `PythonAiClient`가 이를
  `AiJobNotFoundException`으로 변환하고, `JobService`가 `Job`을 `FAILED`로 기록한다
  (`docs/backend/job.md` 3.3).
- 그 외 오류(타임아웃, 5xx, 연결 실패 등)는 `404`가 아닌 다른 방식으로 구분되기만 하면 된다
  (지금 코드는 `AiJobNotFoundException`이 아닌 모든 예외를 "일시 오류"로 본다). backend는
  `Job` 상태를 유지하고 다음 폴링에서 다시 시도한다.
- 두 방향 모두 잘못 쓰면 문제가 생긴다(`JobService.getJob` 참고).
  - ai가 "실제로 없는 작업"에 **5xx**를 잘못 돌려주면 `AiJobNotFoundException`으로 변환되지
    않아 "일시 오류"로 처리된다. 상태가 유지된 채 다음 폴링에서 또 5xx를 받고, 그 다음도
    마찬가지로 — **영원히 끝나지 않고 계속 재시도만 하는 작업**이 된다.
  - 반대로 ai가 "진짜 일시 오류"(타임아웃 등)에 **404**를 잘못 돌려주면 `AiJobNotFoundException`
    으로 변환돼 `Job`이 곧바로 `FAILED`로 확정된다. 사실은 계속 진행 중이거나 나중에 성공할
    작업을 backend가 영구히 실패로 못박아버리는 것이다.
  - 그래서 **404는 정확히 "모르는 jobId"일 때만**, 그 외 모든 경우(일시 장애 포함)는 5xx/연결
    오류로만 응답해야 한다. 둘을 섞으면 "무한 재시도"와 "성급한 FAILED 확정" 중 하나로 반드시
    나타난다.
- 자료 준비/분석/예측 시작 호출이 실패하면(4xx/5xx 무엇이든) backend는 `markFailed`로 끝내고
  자동 재시도하지 않는다(`job.md` 3.3 "다른 도메인이 작업을 시작하는 순서"). 시작 실패를
  "일시 오류"로 구분해 재시도해주는 기능은 MVP에 없다.

**오류 메시지 범위 — 제안**

- `errorMessage`는 backend가 그대로 저장하고(`Job.errorMessage`), 그대로 클라이언트 응답에도
  노출한다(`job.md` 2.4, `GET /api/v1/jobs/{id}`의 `data.errorMessage`). 즉 ai가 보낸 문자열이
  **필터링 없이 외부에 보인다.**
- 그래서 ai의 `errorMessage`는 다음을 포함하지 않아야 한다: 스택트레이스, 내부 파일 경로,
  문서 원문/발췌, 모델명·프롬프트 같은 내부 구성 정보.
- 길이는 500자 이내를 제안한다(근거: `Job.errorMessage` 컬럼 길이에 맞춰 Flyway에서 정할 값과
  맞춰야 하므로, 구체 숫자는 마이그레이션 작성 시 한 번 더 맞춘다). **열림(정확한 글자 수).**

### 2.6 작업 상태 저장 위치 (재시작해도 유지되는지) — 결정: A(SQLite 파일)

**ai 담당자와 합의해 A로 정했다.** ai 서비스는 작업 상태를 SQLite 파일에 저장하고, 단일
인스턴스로 운영한다. 재시작 시 미종료(`QUEUED`/`RUNNING`) 작업은 다시 실행하지 않고 `FAILED`
("서버 재시작으로 작업이 중단되었습니다.")로 확정한다 — backend는 다음 폴링에서 이를 받아
`Job`을 실패로 기록한다. 구현 상세는 `docs/backend/ai-server-design.md` 2.1, 2.7.

아래는 결정 전에 검토한 선택지다.

| 방식 | 장점 | 단점 |
|---|---|---|
| A. ai도 영구 저장 (자체 DB/파일) | ai 재시작·재배포에도 진행 중 작업이 살아남는다 | ai 쪽에 저장소가 하나 더 생긴다(구현·운영 비용) |
| B. ai는 메모리만 유지 (`PythonAiClientStub`과 동일한 방식) | 구현이 단순하다 | ai가 재시작하면 모든 진행 중 작업의 `jobId`가 사라져 backend는 2.5의 "없는 작업"(404)으로 보고 `FAILED` 처리한다. 사용자가 재요청해야 한다 |

- **B를 고르면 ai는 단일 인스턴스이거나, 같은 jobId를 매번 같은 인스턴스로 보내는 라우팅
  (sticky routing)이 있어야 한다.** ai가 여러 인스턴스로 떠 있고 요청이 아무 인스턴스로나
  분산되면, 작업을 시작한 인스턴스 A의 메모리에만 그 jobId가 있고 다른 인스턴스 B는 모른다.
  이어지는 상태 조회가 B로 가면 B는 그 jobId를 모르니 404를 돌려주고, backend는 실제로는
  멀쩡히 실행 중인 작업을 `FAILED`로 확정해버린다. 이 조건(단일 인스턴스 또는 sticky
  routing)을 지키지 못하면 B는 선택지에서 제외해야 한다.
- backend 쪽은 어느 쪽이든 영향받지 않는다(`Job`은 이미 PostgreSQL에 영구 저장되고, "ai에 없는
  작업"을 `FAILED`로 처리하는 경로가 있다). 그래서 이 선택은 **ai 담당자의 운영 편의와 재배포
  빈도, 그리고 인스턴스를 여러 개 띄울 계획이 있는지에 달려 있다.**
- 제안하지는 않지만 참고: K8s 전환(#43) 이후 ai/파이프라인 쪽 파드가 아직 없어(`infra/k8s`에
  ai 매니페스트 없음) 재배포 빈도·재시작 패턴을 가늠하기 어렵다. 그 매니페스트가 나오는
  시점에 다시 논의하는 게 맞다.

### 2.7 결과 보관 기간, backend가 결과를 가져가는 시점의 책임 — 제안 + 열림

- backend는 결과 본문을 `Job` 테이블에 두지 않는다(`job.md` "작업 결과 본문은 담지 않는다").
  `Job`이 `SUCCEEDED`로 바뀔 때 호출한 `getJobStatus`의 `result`를 대상 도메인(예:
  `AnalysisService`)이 그 자리에서 읽어 자신의 테이블(`AnalysisFinding` 등)에 영구 저장하는
  모양을 전제로 한다.
- **문제**: `JobService.getJob`은 작업이 이미 종료 상태면 ai를 다시 호출하지 않는다
  (`job.md` 3.3 "종료됐거나 externalJobId가 없으면 그대로 반환"). 즉 `SUCCEEDED`로 전이되는
  **그 한 번의 호출에서** 대상 도메인이 결과 저장까지 성공해야 한다. 그 저장이 실패하면
  (DB 오류 등) 재시도할 방법이 지금 설계에는 없다. 이 흐름을 어떻게 안전하게 만들지는
  작업별 이슈(분석/예측)에서 정할 몫이지만, 공통 규약 차원에서 ai에게 다음을 요구한다.
- **요구(제안)**: ai는 `SUCCEEDED`/`FAILED`가 된 뒤에도 같은 `jobId`로 같은 `result`를
  최소 보관 기간 동안 **반복 조회 가능**해야 한다(멱등 조회). backend가 저장에 실패해 다음
  폴링에서 다시 같은 결과를 받아 재시도할 여지를 남기기 위함이다.
- 보관 기간의 정확한 값은 **열림**(제안: 24시간, ai 쪽 저장 비용과 함께 확정). 보관 기간이
  지난 뒤 조회하면 2.5의 "없는 작업"(404)과 같게 처리한다.
- **더 근본적인 문제**: backend는 "조회할 때만 갱신"한다(`job.md` "MVP 선택", `JobService`
  상단 문서). 즉 클라이언트가 폴링을 멈추면(브라우저를 닫는 등) `getJobStatus`를 다시 호출할
  일이 없다. ai가 이미 `SUCCEEDED`를 만들어놓고 보관 기간이 지나 결과를 지워버리면, backend는
  그 사실을 전혀 모른 채 나중에(보관 기간이 지난 뒤) 누군가 폴링했을 때 "없는 작업"(404)으로
  받아 `FAILED`로 확정한다 — **실제로는 성공한 작업을 영구히 실패로 기록하는 것**이다. 보관
  기간을 아무리 늘려도, 그 기간 안에 아무도 폴링하지 않으면 같은 일이 벌어진다.
- 이 문제는 이 문서만으로 닫을 수 없다. 둘 중 하나가 필요하다: (a) `job.md` 7번 미정 항목인
  **갱신 배치**(트리거 주체 미정, 스케줄러가 클라이언트 폴링과 무관하게 미종료 작업을 주기적으로
  확인)를 먼저 도입해 보관 기간 안에 backend가 반드시 한 번은 결과를 가져가게 하거나, (b) ai가
  결과를 backend의 명시적인 "확인(ack)" 없이는 지우지 않는 방식(보관 기간을 사실상 무기한에
  가깝게 둠)으로 바꾼다. **열림 — 갱신 배치 도입 여부가 먼저 정해져야 보관 기간도 의미가
  생긴다.**
- **갱신 배치를 도입해도 그것만으로는 안 풀린다.** 지금 `JobService.getJob`(`job/service/
  JobService.java`)은 `Job.status`만 `syncFrom`으로 동기화하고, `JobStatusResponse.result`를
  대상 도메인(`AnalysisService` 등)에 넘기는 경로가 코드 어디에도 없다. 즉 배치가 그냥 이
  메서드를 주기적으로 호출하도록만 만들면, `Job`은 `SUCCEEDED`로 바뀌지만 `result`는 아무도
  저장하지 않고 버려진다 — 그리고 한번 `SUCCEEDED`가 되면 `getJob`은 더 이상 ai를 호출하지
  않으므로(종료 상태) 그 뒤로는 그 `result`를 다시 받아올 방법도 없다.
- 그래서 **상태 동기화와 결과 저장은 한 묶음으로 설계해야 한다**: ai가 `SUCCEEDED`를 돌려주면
  (ㄱ) 먼저 대상 도메인이 `result`를 자신의 테이블에 저장하고, (ㄴ) 그 저장이 성공했을 때만
  `Job`을 `SUCCEEDED`(종료)로 확정한다. 저장이 실패하면 `Job`은 아직 비종료 상태로 남겨
  다음 배치/폴링에서 ai에 같은 `jobId`로 다시 물어 재시도할 수 있게 한다. 지금처럼 "조회 즉시
  `Job.status`부터 종료 확정"하는 순서로는, 배치를 도입해도 결과 저장 실패를 복구할 방법이
  없다. 이 재구성(`getJob`을 어떻게 나눌지, 대상 도메인과의 연결 지점을 어디에 둘지)은 공통
  `job` 도메인과 각 대상 도메인(분석/예측/자료 준비)이 함께 정할 몫이라 **후속 이슈로 올린다
  (4번).**
- **반영 (#63)**: `getJob`은 ai가 `SUCCEEDED`를 주면 작업 행을 잠근 한 트랜잭션에서 대상 도메인의
  `JobResultHandler`가 결과를 저장하고 완료를 확정한다. 저장이 실패하면 둘 다 되돌리고 다음 조회에서
  같은 `jobId`로 다시 받아 재시도한다(실패 횟수 한도 기본 5회, 넘으면 `FAILED`). ai/server는 끝난 작업의
  결과를 SQLite에 계속 보관하므로(#48) 재시도할 수 있다. 세부는 `job.md` 3.3.

### 2.8 중복 요청 처리, 동시 실행 개수 — 제안

- **멱등성**: "backend가 시작 요청을 자동 재시도하지 않는다"는 사실만으로 멱등성이 필요
  없다고 결론 내릴 수 없다. **자동 재시도가 없어도** 문제가 생기는 경우가 있다: backend가
  시작 요청을 보내고 ai가 작업을 큐에 올렸는데, 그 응답(`202`+jobId)이 네트워크 타임아웃 등으로
  backend에 돌아오지 못하면, backend는 `markFailed`로 끝낸다(`job.md` 3.3) — 하지만 ai 쪽
  작업은 이미 시작돼 계속 실행된다. 이 작업은 `externalJobId`가 backend 어디에도 저장되지
  않아 **추적할 수 없는 채로 남는다.** 이 상태에서 사용자가 같은 작업을 다시 요청하면 새
  `Job`과 새 시작 요청이 생겨 실제로는 두 개가 동시에 실행되고, 먼저 시작된 작업은 끝나도
  아무도 그 결과를 가져가지 않는다.
- 이건 자동 재시도 여부와 무관하게 **시작 호출 자체의 응답 유실**에서 생기는 문제라, 멱등성
  설계를 통째로 후속 과제로 미루지 않는다. 다음을 **열림**으로 남긴다.
  - **멱등키는 `Job.id`가 될 수 없다.** 사용자가 재요청하면 backend는 (실패로 끝난 첫
    `Job`과는 별개로) **새 `Job`을 새 id로 만든다.** 그 새 `Job.id`를 키로 보내면 ai 입장에서는
    "처음 보는 키"라서 중복으로 인식하지 못하고 그대로 또 시작해버린다 — 막으려던 중복 실행이
    그대로 재현된다. 키는 **같은 사용자 작업(의도)을 가리키는, 재요청에도 바뀌지 않는 값**이어야
    한다. 후보는 대상 식별자 자체(`documentVersionId`/`analysisRunId` 등 — 이미 `AnalysisRequest`,
    `PredictionRequest`, `DocumentPreparationRequest`에 들어있는 값) 쪽이 유력하지만, "같은
    대상에 같은 종류의 작업을 다시 요청하는 것"이 도메인별로 항상 1:1로 들어맞는지는 작업별
    이슈(분석/예측/자료 준비)에서 확인이 필요하다.
  - **키를 찾았을 때(ai가 기존 jobId를 돌려줬을 때) 어떻게 연결할지도 정해야 한다.** 첫 시도의
    `Job`은 이미 `FAILED`(종료 상태)로 남아 있고, `Job`의 상태 전이 규칙상 종료 상태에서는
    되돌릴 수 없다(`job.md` 3.1). 그러니 되살리는 대상은 첫 `Job`이 아니라 **재요청으로 새로
    만든 `Job`**이어야 한다 — 새 `Job`이 ai로부터 돌려받은(기존) `externalJobId`로 곧바로
    `markStarted`를 호출해 그 작업을 이어받는다. 첫 `Job`(이미 `FAILED`)은 고치지 않고 그냥
    고아 기록으로 남는다(`Job`은 애초에 수정 없이 쌓는 이력이라 `backend/CLAUDE.md` 6번과도
    맞는다). 혹은 backend가 시작 호출 타임아웃 뒤 "ai 쪽에 이미 생겼는지" 확인할 별도 조회
    경로를 두는 방식도 대안이다. 이 중 어느 쪽으로 할지, 정확한 키는 무엇인지는 ai 담당자와
    후속 이슈(4번)에서 확정한다. (2.10의 타임아웃 설정과도 맞물린다: 타임아웃을 짧게 잡을수록
    "ai는 성공했는데 backend만 실패로 본" 상황이 더 자주 생긴다.)
- **동시 실행 개수**: 재무 데이터 적재는 이미 "한 번에 한 건"으로 backend가 제한한다
  (`entities.md` 3.3 "이미 실행 중인 적재가 있으면 건너뛴다"). 자료 준비/분석/예측은
  사용자별로 독립적인 작업이라 backend는 동시 실행 수를 제한하지 않는다. ai 서비스 자체의
  동시 처리 한도(워커 수, 큐 길이 등)는 ai 담당 영역이며 backend가 알 필요는 없다 — 다만
  한도를 넘겨 받은 요청을 어떻게 응답할지는 **열림**(예: `429` vs 큐에 쌓아두고 `202`).

### 2.9 허용 문서·기준 시점을 backend가 확정해 전달하고 ai가 바꾸지 못하게 하는 방식 — 제안 + 발견한 간극

- 원칙은 이미 세워져 있다(`backend/CLAUDE.md` 6번, `entities.md` 5): 허용 문서 범위와
  기준 시점은 서버가 강제하고, LLM이 인자로 정하지 않는다. `AnalysisRequest`가 이미
  `allowedDocumentVersionIds`(목록)와 `asOfDate`를 갖고 있다.
- **간극**: `PredictionRequest`(현재 초안)에는 `analysisRunId`와 `asOfDate`만 있고
  `allowedDocumentVersionIds`가 없다. 그런데 `entities.md` 3.5는 "ai는 기준 시점 이전에
  발행된 자료만 사용한다. 이후 발행 문서는 서버가 허용 문서에서 제외한다"고 적어, 예측도
  명시적 허용 목록이 필요함을 전제로 한다(예측 시점과 분석 시점 사이에 새로 들어온 문서가
  있을 수 있다). `PythonAiClient`/`PredictionRequest`에 `allowedDocumentVersionIds` 추가가
  필요해 보인다 — **후속 이슈로 올림(4번)**, 확정은 prediction 작업별 이슈에서.
- **ai가 바꾸지 못하게 하는 방식(제안)**: 전달은 "강제"지만 확인은 "검증"으로 한다.
  1. backend가 허용 문서 목록을 계산해 요청 바디에 통째로 넘긴다(이미 그렇다).
  2. ai/`mcp-server`의 조회 도구는 이 목록으로만 검색 범위를 제한한다(LLM이 날짜·범위를
     스스로 고르게 하지 않는다).
  3. ai가 돌려준 결과(`evidenceRef` 등)가 허용 목록 밖의 문서를 가리키면, **backend가 저장
     시점에 걸러낸다.** 숫자는 코드로 계산하고 공시와 대조한다는 원칙(`CLAUDE.md` 4번)과
     같은 맥락이다 — ai의 응답이라도 그대로 믿지 않는다.
  - 걸러낸 뒤 분석 전체를 실패로 볼지, 그 항목만 버리고 나머지는 저장할지는 **열림**
    (제안: 그 항목만 버리고 경고 로그를 남긴다 — 전체 실패는 사용자 경험상 과하다).

### 2.10 호출 인증, 타임아웃 — 구현 (#114)

- **인증**: backend ↔ ai는 같은 신뢰 경계 안의 내부 호출이다(외부에 노출되지 않음). 공유
  비밀키를 헤더로 보내는 방식을 제안한다: `X-Internal-Api-Key: {값}`. 값은 환경변수로 주입하고
  (`.env.example`에는 변수명만, 루트 `CLAUDE.md` 6번) backend·ai 양쪽에 같은 값을 둔다.
  변수명: backend `AI_SERVICE_API_KEY`, ai `INTERNAL_API_KEY`. mTLS 등 더 강한 방식은 MVP 범위를 넘는다고 본다.
- **backend 설정** (`infra.python-ai-client`, #114): `mode`(`AI_SERVICE_MODE`, 기본 `stub` / 실제 호출 `http`),
  `base-url`(`AI_SERVICE_BASE_URL`), `api-key`(`AI_SERVICE_API_KEY`). `http`인데 주소나 비밀키가 비어 있으면 기동 시점에 실패한다.
  구현은 `PythonAiClientHttp`(Spring `RestClient` + JDK HTTP 클라이언트).
- **HTTP/1.1 고정**: JDK HTTP 클라이언트 기본값(HTTP/2)은 `http://` 주소에 h2c 업그레이드를 시도하는데,
  ai/server(uvicorn)는 그 요청의 본문을 받지 못해 작업 시작이 `422`로 거절됐다(로컬 확인). backend는 HTTP/1.1로만 보낸다.
- **타임아웃(설정으로 조정 가능)**: 연결 타임아웃 2초(공통). 시작 호출(`startXxx`)은
  읽기 타임아웃 5초 — 작업을 큐에 올리는 것만 하므로 오래 걸리지 않아야 한다. 상태 조회는
  읽기 타임아웃 3초 — 폴링마다 호출되므로 더 짧게 잡는다. 분석·예측 자체의 실행 시간은
  비동기 처리로 가려지므로 이 타임아웃들과 무관하다.
- 타임아웃이 발생하면 2.5의 "일시 오류"로 처리한다(시작 호출은 `markFailed`, 상태 조회는
  상태 유지 후 재시도).
- **오류 구분(구현)**: 상태 조회 `404`만 `AiJobNotFoundException`이고, 그 밖의 실패(타임아웃·연결 실패·`5xx`·형식이 틀린 응답)는
  `AiCallFailedException`이다. 시작 호출의 `4xx`/`5xx`도 `AiCallFailedException`이며, 미등록 작업의 `501`(#86)은
  "ai 서비스가 아직 지원하지 않는 작업입니다."로 구분한다. 예외 메시지와 로그에는 요청·응답 본문과 비밀키를 넣지 않는다.

### 2.11 파일 접근 방식 (`fileStorageKey`) — 결정 (2026-11-26, #86)

| 자료 | 저장 위치 (배포) | 관리 |
|---|---|---|
| 사용자 입력 파일 (예산서, 근거자료 업로드) | S3 (`S3_BUCKET`) — backend `FileStorage` (#89) | backend |
| 공시 원본 (DART 등, `pipelines/collectors` 수집분) | ai 쪽 저장소 | ai |

- `fileStorageKey`는 backend `FileStorage`가 발급한 키다. 형식은 `<UUID><확장자>`이고, S3에서는 버킷
  최상위 키, 로컬에서는 backend 저장 폴더(`FILE_STORAGE_DIR`) 기준 파일 이름이다.
- 자료 준비 작업에서는 ai/server가 이 키로 원본을 로컬 경로로 준비해 파이프라인에 넘긴다
  (`ai-server-design.md` 2.6.1). ai 파이프라인 코드는 S3를 직접 다루지 않는다.
- 그래서 ai/server 실행 환경에는 S3 읽기 권한(IRSA)과 `S3_BUCKET`·`AWS_REGION`이 필요하다.
  ai/server의 K8s 매니페스트와 권한 구성은 infra 담당이다(아직 없음).
- backend는 공시 원본을 저장하거나 S3에 올리지 않는다.
- 이전 제안(backend·ai가 같은 공유 볼륨을 마운트)은 쓰지 않는다. 로컬 개발에서만 두 서버가 같은 폴더를 본다.
- 업로드 원본의 보관·삭제 정책은 아직 정하지 않았다(루트 `CLAUDE.md` 7번).

### 2.12 파이프라인 호출 방식 (파이썬 함수 호출 여부) — 제안, ai 담당자 최종 합의 필요

- **제안: HTTP(FastAPI)**, 함수 직접 호출(JNI, 프로세스 내 Python 임베딩 등)은 채택하지 않는다.
- 이유:
  1. 언어가 다르다(backend는 Java/Spring, ai는 Python). 같은 프로세스에 묶으면 배포·장애
     격리가 모두 얽힌다.
  2. `PythonAiClient`는 이미 "구현체는 설정으로 교체"(`infra.python-ai-client.mode`,
     지금은 `stub`)하는 모양으로 설계돼 있다. 함수 호출은 이 교체 가능성(원격 HTTP 구현체로
     바꿔 끼우는 것)과 맞지 않는다.
  3. K8s 전환(#43)으로 backend/frontend는 이미 별도 파드가 됐다(DB는 #76부터 RDS). ai도 같은 방향으로
     갈 것이 자연스럽고, 별도 파드라면 네트워크 호출(HTTP)만 가능하다.
- **열림**: ai 담당자가 다른 이유로 함수 호출이나 다른 프로토콜(gRPC 등)을 선호하면 이 제안을
  바꿀 수 있다. 이 문서의 나머지 항목(2.4, 2.9, 2.10)은 HTTP를 전제로 적었다.

## 3. 아직 정해지지 않은 것 (요약)

| 항목 | 관련 절 | 비고 |
|---|---|---|
| 엔드포인트 경로·라우팅 방식 | 2.4 | ai 담당자의 FastAPI 설계 선호에 맞춤 |
| `errorMessage` 최대 길이 | 2.5 | Flyway 작성 시 함께 확정 |
| 결과 보관 기간 정확한 값, 갱신 배치 도입 여부 | 2.7 | 제안 24시간이지만 갱신 배치(`job.md` 7번) 없이는 보관 기간이 지나기 전 backend가 못 가져갈 수 있음 |
| ai 서비스 동시 처리 한도 초과 시 응답 | 2.8 | `429` vs 큐잉 |
| 시작 호출 응답 유실 시 멱등성(중복 실행 방지) | 2.8 | 멱등키(`Job.id` 아님 — 재요청에도 안 바뀌는 대상 식별자 필요) 또는 별도 확인 경로, 기존 jobId 발견 시 새 `Job`에 연결하는 방식 |
| 결과를 "언제" 대상 도메인에 저장하는지(`getJob`과 결과 저장의 관계) | 2.7 | 지금 `getJob`은 `Job.status`만 동기화. 저장 성공 후에만 종료 확정하도록 재구성 필요 |
| `PredictionRequest`에 `allowedDocumentVersionIds` 추가 여부 | 2.9 | prediction 작업별 이슈에서 최종 확정 |
| 허용 목록 밖 근거를 걸러낼 때 분석 전체 실패 여부 | 2.9 | 제안: 해당 항목만 버림 |
| ai/server K8s 매니페스트와 S3 읽기 권한(IRSA) 구성 | 2.11 | infra 담당 영역과 협의 |
| 파이프라인 호출 방식(HTTP 최종 확정) | 2.12 | ai 담당자 합의 필요 |
| 재무 데이터 적재·예측 평가가 이 폴링 패턴을 그대로 쓸지 | 2.4 | 작업별 이슈(datasync, prediction)에서 결정 |

## 4. 후속 이슈 목록 (제안)

합의가 끝나면 아래 작업이 필요하다. 실제 이슈 등록은 사용자 요청 시에만 한다(루트 `CLAUDE.md` 5번).

| 영역 | 내용 |
|---|---|
| ai | FastAPI 껍데기 구현: 2.4의 엔드포인트, `X-Internal-Api-Key` 검증 미들웨어 |
| backend | ~~`PythonAiClient` 실제 HTTP 구현체 추가~~ → #114에서 완료(`mode=http`) |
| backend | `PredictionRequest`에 `allowedDocumentVersionIds` 추가, `AnalysisRequest`의 `profileSnapshotJson`은 profile 패키지 확정 후 정리 |
| backend | 예측 평가, 재무 데이터 적재용 `PythonAiClient` 메서드 추가 (결과 보관·폴링 패턴 적용 여부는 각 작업별 이슈에서 먼저 확정) |
| backend | ~~`job` 도메인 재구성: 결과 저장 성공 후에만 종료 확정~~ → #63에서 완료 |
| backend | 시작 요청 멱등키 설계: 재요청에도 유지되는 키 선정, ai가 기존 jobId를 돌려줬을 때 새 `Job`에 `markStarted`로 연결하는 처리 추가 (2.8) |
| infra | ai/server K8s 매니페스트, S3 읽기 권한(IRSA)·`S3_BUCKET`·`AWS_REGION` 전달 — backend/ai 양쪽과 협의 |
| backend/ai 공통 | `AI_SERVICE_API_KEY`/`INTERNAL_API_KEY` 환경변수를 `.env.example`에 추가 |

## 5. 다시 확인할 시점

| 시점 | 확인할 것 |
|---|---|
| ai 담당자 확인 후 | 이 문서의 "제안"·"열림" 표시를 실제 합의 결과로 갱신, 이슈 #51 체크리스트 반영 |
| ai/pipelines의 FastAPI 껍데기를 만들 때 | 2.4의 경로·응답 형식이 실제 구현과 일치하는지 |
| infra가 ai/server K8s 매니페스트를 만들 때 | 2.11의 S3 읽기 권한과 환경변수가 들어가는지 |
| prediction 작업별 이슈를 진행할 때 | 2.9의 `allowedDocumentVersionIds` 간극, 2.4의 평가 폴링 여부 |
| api-spec 문서가 만들어질 때 | 2.5의 에러 코드 접두사(`job.md` 미정 항목과 공유), 폴링 간격 권장값 |
