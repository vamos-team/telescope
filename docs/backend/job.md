# job 도메인

> 작성자 : 윤혜정

> 오래 걸리는 작업(자료 준비, 분석, 예측)의 상태를 저장하고 폴링으로 조회하는 부분이다. 이슈 #35 기준.
> 코드가 바뀌면 이 문서도 함께 고친다. 6~8번(문제, 확장, 재확인 시점)은 특히 자주 확인한다.

## 1. 역할

- 자료 준비, 분석, 예측은 수 초~수 분이 걸려 한 번의 요청으로 끝낼 수 없다. 요청하면 `jobId`를 돌려주고, 클라이언트가 `GET /api/v1/jobs/{id}`로 상태를 확인한다(폴링). 콜백은 없다.
- 실제 작업은 ai 서비스가 수행하고, backend는 그 **상태의 사본**을 `job` 테이블에 둔다. 이 도메인은 그 사본을 안전하게 저장하고 최신으로 맞추는 일을 맡는다.
- `Job`은 대상(`DocumentVersion`/`AnalysisRun`/`PredictionRun`)을 모른다. 대상이 `jobId`로 `Job`을 가리킨다.

## 2. 구성

```
job/
├── entity/      Job, JobType, JobStatus
├── repository/  JobRepository
├── service/     JobService
├── controller/  JobController
└── dto/         JobResponse
```

연관 코드: `infra.python.AiJobStatus`·`AiJobNotFoundException`(ai 쪽 상태와 예외), `global.config.ClockConfig`(시각 주입), `ErrorCode.JOB_INVALID_TRANSITION`(`J001`, 409)

## 3. 구현

### 3.1 상태 전이

```
QUEUED ──▶ RUNNING ──▶ SUCCEEDED
   │          │
   └──────────┴──────▶ FAILED
```

| 현재 | 갈 수 있는 상태 |
|---|---|
| `QUEUED` | `RUNNING`, `FAILED` |
| `RUNNING` | `SUCCEEDED`, `FAILED` |
| `SUCCEEDED`, `FAILED` | 없음 (종료 상태) |

규칙은 `JobStatus.canTransitionTo`가 갖고, `Job`이 상태를 바꿀 때마다 검사한다. 위반하면 `BusinessException(J001)`이다.

### 3.2 Job 엔티티

컬럼은 `V3__create_job.sql`과 같다: `jobType`, `status`, `externalJobId`, `errorMessage`, `startedAt`, `finishedAt`.

`@Setter`가 없다. 상태가 바뀌는 길을 아래 메서드로만 열어, 모든 변경이 전이 규칙을 지나가게 한다.

| 메서드 | 하는 일 |
|---|---|
| `Job.create(jobType)` | 새 작업을 `QUEUED`로 만든다 (정적 팩토리) |
| `start(now)` | `QUEUED → RUNNING`, `startedAt` 기록 |
| `succeed(now)` | `RUNNING → SUCCEEDED`, `finishedAt` 기록 |
| `fail(message, now)` | `QUEUED/RUNNING → FAILED`, `errorMessage`와 `finishedAt` 기록 |
| `assignExternalJobId(id)` | ai 서비스가 발급한 작업 ID 저장 |
| `isTerminal()` | 종료 상태인지 확인 |
| `syncFrom(status, message, now)` | ai가 알려 준 상태를 반영 |
| `transitionTo(next)` (private) | 전이를 검사하고 상태를 바꾼다. `start/succeed/fail`이 모두 거친다 |

**`syncFrom`**: `start/succeed/fail`은 허용되지 않은 전이면 예외를 던진다. 폴링으로 받는 ai 상태는 같은 값이 반복되거나 단계를 건너뛰는 것이 정상이라 별도로 처리한다.

| ai가 알려 준 상태 | 처리 |
|---|---|
| 현재와 같은 상태 | 무시 |
| 이미 종료된 작업 | 무시 |
| `QUEUED` | 되돌리지 않고 무시 |
| `RUNNING` | `start` |
| `SUCCEEDED` (현재 `QUEUED`) | `start` 후 `succeed` (`startedAt`도 기록) |
| `FAILED` | `fail` |

### 3.3 JobService

| 메서드 | 용도 |
|---|---|
| `create(jobType)` | `QUEUED` 작업 생성 |
| `markStarted(jobId, externalJobId)` | ai 시작 요청이 성공했을 때. 외부 ID 저장 후 `RUNNING` |
| `markFailed(jobId, message)` | ai 시작 요청이 실패했을 때 |
| `getJob(jobId)` | 상태 조회. 미종료 작업이면 ai 상태를 받아 저장한 뒤 반환 |

**다른 도메인이 작업을 시작하는 순서**

1. 트랜잭션 1: 대상 저장과 `create`를 함께 커밋
2. 트랜잭션 밖: `PythonAiClient.startXxx` 호출
3. 트랜잭션 2: 성공하면 `markStarted`, 실패하면 `markFailed`

**`getJob`의 동작 (조회할 때 갱신)**

1. 작업 조회 (짧은 트랜잭션)
2. 종료됐거나 `externalJobId`가 없으면 그대로 반환 (ai 호출 없음)
3. `PythonAiClient.getJobStatus` 호출 (트랜잭션 밖)
4. 작업 행을 잠그고(`findByIdForUpdate`) 다시 읽어 `syncFrom`으로 반영한 뒤 반환 (짧은 트랜잭션)

| ai 호출 결과 | 처리 |
|---|---|
| `AiJobNotFoundException` (ai에 없는 작업) | `FAILED`로 기록 |
| 그 외 오류 (네트워크 등) | 상태 유지, 다음 폴링에서 재시도 |

### 3.4 API

#### 📌 개요

작업(자료 준비, 분석, 예측)의 현재 상태를 조회한다. 아직 끝나지 않은 작업이면 ai 서비스의 최신 상태를 받아 저장한 뒤 돌려준다. 클라이언트는 `SUCCEEDED` 또는 `FAILED`가 될 때까지 이 API를 폴링한다.

#### 🔗 Endpoint

`GET /api/v1/jobs/{id}`

- 로그인한 사용자 누구나 조회할 수 있다(MVP). 인증은 user 이슈 이후 적용된다.

#### 📤 Request Body

없음

#### Request Param

| 속성 | 설명 | 타입 |
| --- | --- | --- |
| `id` | 작업 ID. 작업을 시작하는 API가 반환한 `jobId` (path) | number |

---

#### 📥 Response Body (200)

| 속성 | 설명 | 타입 |
| --- | --- | --- |
| `data.id` | 작업 ID | number |
| `data.jobType` | `DOCUMENT_PREPARATION` / `ANALYSIS` / `PREDICTION` | string |
| `data.status` | `QUEUED` / `RUNNING` / `SUCCEEDED` / `FAILED` | string |
| `data.startedAt` | 시작 시각. 시작 전이면 `null` | string \| null |
| `data.finishedAt` | 종료 시각. 끝나지 않았으면 `null` | string \| null |
| `data.errorMessage` | 실패 사유. 실패가 아니면 `null` | string \| null |
| `error` | 성공이면 `null` | null |

```json
{
  "data": {
    "id": 42,
    "jobType": "ANALYSIS",
    "status": "RUNNING",
    "startedAt": "2026-11-16T09:30:00",
    "finishedAt": null,
    "errorMessage": null
  },
  "error": null
}
```

- 작업 결과 본문은 담지 않는다. 결과는 해당 도메인의 조회 API로 가져온다.
- 응답 형식은 코드의 `ApiResponse`(`data`, `error`)를 따른다. `status`, `message` 필드는 없다.

#### 📥 Response Body (에러)

```json
{
  "data": null,
  "error": { "code": "C002", "message": "ID 999번 작업을 찾을 수 없습니다." }
}
```

| HTTP | 코드 | 상황 |
|---|---|---|
| 404 | `C002` | 없는 작업 |

## 4. 왜 이렇게 했나

| 결정 | 이유 | 버린 대안 |
|---|---|---|
| 엔티티가 전이 규칙을 가진다 (`@Setter` 없음) | 규칙이 서비스 여러 곳에 흩어지면 한 곳만 빠뜨려도 잘못된 상태가 DB에 들어간다 | 서비스에서 상태를 직접 바꾸고 검사 |
| `QUEUED → FAILED` 허용 | ai 시작 요청이 실패하면 작업이 영원히 `QUEUED`로 남는다 | 이슈 원안(`QUEUED → RUNNING → 종료`만) |
| 같은 상태 반복은 예외가 아니라 무시 | 폴링은 같은 상태를 반복해 받는 것이 정상이다 | 모든 비허용 전이를 예외로 처리 |
| **조회할 때 갱신** | 사용자가 적고, 갱신 배치의 트리거 주체(Jenkins/Spring)가 아직 정해지지 않았다 | 주기 배치로 갱신 |
| ai 호출은 트랜잭션 밖 | 외부 호출 동안 DB 커넥션을 붙잡지 않는다 (`backend/CLAUDE.md` 규칙) | 한 트랜잭션 안에서 호출 |
| 저장 직전에 행을 잠그고(`SELECT ... FOR UPDATE`) 다시 읽어 `syncFrom` | 같은 작업의 동시 갱신을 한 줄로 세운다. 잠금 읽기는 가장 최근에 커밋된 값을 읽으므로, 뒤에 온 요청은 앞선 요청의 종료 상태를 보고 무시한다. 마이그레이션이 필요 없다. 잠금은 짧은 저장 트랜잭션에만 걸고 ai 호출은 잠금 밖이다 | 낙관적 락(`@Version`, 컬럼 추가 필요), 락 없이 재조회만 (코드 리뷰에서 경쟁 가능성 지적) |
| enum을 `AiJobStatus`(ai 쪽)와 `JobStatus`(DB)로 분리 | DB 값은 한번 쌓이면 바꾸기 어렵고, ai 계약은 초안이라 값이 늘 수 있다. 변환 한 곳만 고치면 된다 | 하나의 enum 공유 |
| "ai에 없는 작업"과 "일시 오류"를 다르게 처리 | 없는 작업은 기다려도 끝나지 않지만, 일시 오류는 다시 시도하면 된다 | 모든 오류를 `FAILED`로 |
| `Clock` 주입 | `now()`를 직접 찍으면 테스트가 불안정하다 | `LocalDateTime.now()` 직접 호출 |
| 조회는 로그인한 사용자 전체에 허용 | `Job`에 소유자 컬럼이 없다. 응답에 결과 본문이 없어 MVP에서는 감수한다 | `Job`에 소유자 추가 |

## 5. 미정

| 항목 | 현재 처리 | 정해지는 곳 |
|---|---|---|
| 조회 권한의 최종 정책 (본인 작업만?) | 로그인한 사용자 전체 | 대상 도메인 구현 시 |
| 갱신 배치 도입 여부와 트리거 주체 | 조회할 때만 갱신 | datasync 스케줄러 결정과 함께 |
| 오래된 `QUEUED`/`RUNNING` 작업의 타임아웃 정책 | 없음 | 정리 배치 결정 시 |
| ai 호출 계약 (`PythonAiClient`는 가칭 초안) | Stub 기준 | 실제 ai 서비스 연동 시 |
| 시간대 | 시스템 기본값 | 시간대 정책 결정 시 (api-spec 미정 항목) |
| 폴링 간격 권장값 | 정해지지 않음 | api-spec |
| 에러 코드 접두사(`J`) | 임시 | api-spec 확정 시 |
| 작업 취소, 재시도 | 지원하지 않음 | 요구가 생길 때 |

## 6. 알려진 문제와 한계

| 문제 | 영향 | 비고 |
|---|---|---|
| GET이 쓰기를 한다 | 조회가 부수 효과를 가진다. 조회 안 하는 작업은 갱신되지 않는다 | MVP 선택 |
| 진행 중인 작업은 폴링마다 ai를 호출한다 | 사용자와 폴링 빈도가 늘면 ai 호출이 비례해 늘고, 같은 작업의 동시 폴링이 각자 호출한다 | 종료되면 호출이 멈춘다 |
| 같은 작업을 동시에 폴링하면 저장 단계가 줄을 선다 | 행 잠금 때문에 뒤 요청이 앞 요청의 짧은 트랜잭션이 끝날 때까지 기다린다. 잠금 시간이 짧아 지금은 문제 없다 | 대기가 길어지면 `@Version` 방식을 검토 |
| `markStarted`, `markFailed`는 잠금 조회를 쓰지 않는다 | 작업을 만든 쪽이 한 번씩 호출하는 흐름이라 경쟁 가능성이 낮다고 봤다. `getJob` 갱신과 동시에 일어나면 어긋날 수 있다 | 문제가 보이면 두 메서드도 잠금 조회로 바꾼다 |
| 오래된 `QUEUED`/`RUNNING` | 생성 직후 서버가 죽거나, 일시 오류가 계속되면 상태가 영원히 안 끝난다 | 타임아웃 정리가 필요 |
| ai의 `errorMessage`를 그대로 저장하고 응답한다 | 내부 사정이나 문서 내용이 담기면 클라이언트에 노출된다 | 실제 ai 연동 전에 정제 방식 결정 필요 |
| "없는 작업" 판단이 ai 구현체에 의존한다 | 실제 구현체가 일시 장애도 이 예외로 던지면 멀쩡한 작업을 `FAILED`로 만든다 | 구현체는 404일 때만 던져야 한다 |
| 순차 ID와 소유자 미검사 | 남의 작업 상태를 추측해 볼 수 있다 | 인증·소유자 제한 때 해결 |
| 테스트가 가짜 ai 대역 기준이다 | 실제 Stub·HTTP 구현체와의 통합, 대상 도메인과의 흐름은 검증하지 않았다 | 연동 시 추가 |

## 7. 확장

| 하려는 것 | 방법 |
|---|---|
| 갱신 배치 | 스케줄러가 미종료 작업을 훑어 `getJob`의 갱신 로직(3~4단계)을 재사용한다. 이후 `getJob`은 순수 조회로 되돌릴 수 있다 |
| 본인 작업만 조회 | `Job`에 소유자 컬럼을 추가(새 마이그레이션)하거나, 대상 도메인이 `jobId`로 소유자를 조회해 검사한다 |
| 동시 갱신 방어 강화 | 잠금 대기가 문제되면 `job.version` 컬럼과 `@Version`(낙관적 락)으로 전환한다 (새 마이그레이션) |
| 타임아웃 정리 | `startedAt`/`createdAt` 기준 N분이 지난 미종료 작업을 `FAILED`로 기록하는 배치 |
| 작업 종류 추가 | `JobType` 값 추가. 컬럼이 VARCHAR라 마이그레이션은 필요 없고, 대상 도메인이 `jobId`로 연결한다 |
| 취소 | `CANCELED` 상태 추가: `JobStatus` 전이 표, `AiJobStatus` 변환, 종료 상태 판정을 함께 고친다 |
| 실제 ai 구현체 | `PythonAiClient` HTTP 구현체를 추가하고, 404만 `AiJobNotFoundException`으로 변환한다 |
| 폴링 부하 완화 | 응답에 권장 폴링 간격을 알려 주거나, 짧은 시간 내 같은 작업의 ai 호출을 합친다 |

## 8. 다시 확인할 시점

| 시점 | 확인할 것 |
|---|---|
| user 이슈에서 인증이 붙을 때 | 이 경로에 인증 적용, 401 테스트 추가, 조회 권한 정책 재확인 |
| 첫 대상 도메인(document)을 구현할 때 | `create → markStarted/markFailed` 흐름이 의도대로 쓰이는지, 소유자 제한 방식 결정 |
| 실제 ai 서비스를 연동할 때 | 상태 값 대응, 404 변환, 오류 메시지 노출, 호출 지연 |
| datasync 스케줄러(트리거 주체)가 정해질 때 | 갱신 배치와 타임아웃 정리를 함께 도입할지 |
| 폴링이 느려지거나 ai 호출이 많아질 때 | 조회할 때 갱신 방식을 유지할지 |
| 운영에서 오래된 `QUEUED`/`RUNNING` 작업이 보일 때 | 타임아웃 정리를 바로 도입 |
| api-spec이 확정될 때 | 에러 코드 접두사, 폴링 간격, 시간대 |

## 9. 테스트

| 테스트 | 확인하는 것 |
|---|---|
| `JobTest` | 상태 전이와 `syncFrom`의 모든 분기 (단위) |
| `JobRepositoryTest` | MySQL에서 `ddl-auto: validate` 통과, enum·시각 저장, 잠금 조회 |
| `JobServiceTest` | 조회 시 갱신, 종료 작업의 ai 미호출, ai 오류 처리, 동시 조회에서 먼저 종료된 상태 유지(잠금을 빼면 실패함을 확인). 응답을 정할 수 있는 가짜 ai 대역 사용 |
| `JobControllerTest` | 200, 404 응답 형식 |
