# 재무 데이터 적재 배치 (`datasync`)

> 작성자 : 윤혜정

> 공시 재무 수치(DuckDB)를 새 공시에 맞춰 갱신하는 배치를 정해진 때에 실행하고, 실행 이력과 지금 쓰는 판(버전)을 관리하는 부분이다. 이슈 #135 기준.
> 코드가 바뀌면 이 문서도 함께 고친다.

## 1. 역할과 담당

- **backend**: 배치를 돌리는 일. 언제 시작할지(스케줄), 시작 요청, 결과 확인, 실행 이력(`DataSyncRun`), 지금 쓰는 판(`DataVersion`) 전환. ai/server(backend 담당)에 "데이터 적재" 작업 추가
- **ai**: 수집기(`pipelines/collectors/dart_xbrl.py`)와 적재기(`pipelines/loaders/xbrl.py`) 코드와 그 입출력
- 담당 구분은 사용자 확인(2026-11-27). CLAUDE.md 2장에는 폴더 담당만 있고 "배치를 누가 돌리나"는 없었다.

## 2. 흐름

```
[backend] 스케줄러(cron, 서울 시간) 또는 관리자 수동 실행
   │  ① DB 잠금 → 가장 최근 실행이 끝나지 않았으면 그것을 돌려주고 끝
   │  ② Job(DATA_SYNC, QUEUED) + DataSyncRun 만들기 (한 트랜잭션)
   │  ③ ai/server에 적재 작업 시작 요청 → 성공 markStarted / 실패 markFailed
   ▼
[ai/server] ④ 수집기: 회사마다 DART에서 정기공시 XBRL zip 받기(이미 받은 건 건너뜀) + 택소노미
            ⑤ 적재기: 받아 둔 zip 전체로 새 DuckDB 파일 만들기(.tmp로 만들고 검증한 뒤 이름 바꾸기)
               → <DATASYNC_DATA_DIR>/duckdb/finance-<실행번호>.duckdb
   ▼
[backend] ⑥ 결과 확인 스케줄러(1분마다): 끝나지 않은 적재 작업의 ai 상태를 물음
          ⑦ 성공 → 결과 처리기가 이전 판을 끄고 새 판을 켬(한 트랜잭션, #63) / 실패 → 실패 사유만 남고 이전 판 유지
```

분석은 시작할 때 그 시점의 활성 판 번호를 `AnalysisRun.dataVersionId`로 남긴다(분석 이슈에서 연결, `DataSyncService.activeVersion()`).

## 3. 구성

| 클래스 | 역할 |
|---|---|
| `DataSyncService` | 시작(중복 방지), 결과 확인(`pollUnfinished`), 이력, 활성 판 조회 |
| `DataSyncResultHandler` | `JobResultHandler`(`DATA_SYNC`). 결과의 `duckdbPath`로 새 판을 켜고 이전 판을 끔 |
| `DataSyncScheduler` | 시작 신호(cron)와 결과 확인(주기). 예외는 로그로만 남김 |
| `DataSyncController` | `POST`·`GET /api/v1/data-sync-runs` (관리자 전용) |
| `DataSyncRun`, `DataVersion` | 엔티티. 스키마는 `V5`, `V14` |
| `global/config/SchedulingConfig` | `@EnableScheduling`, 배치 전용 스케줄러 스레드 |
| ai/server `jobs/datasync.py` | 수집기 → 택소노미 → 적재기 순서로 부르는 연결 |

설정(backend `application.yml` `app.datasync`)

| 설정 | 환경변수 | 기본 | 설명 |
|---|---|---|---|
| `cron` | `DATASYNC_CRON` | `-`(꺼짐) | 시작 시각. 배포는 `0 0 3 * * *`(매일 03:00) |
| `poll-interval` | `DATASYNC_POLL_INTERVAL` | `PT1M` | 결과 확인 주기 |
| `corp-codes` | `DATASYNC_CORP_CODES` | 비어 있음 | 대상 회사 DART 고유번호(쉼표). 비어 있으면 시작하지 않음 |
| `years` | `DATASYNC_YEARS` | 5 | 오늘부터 몇 년 전 공시까지 받을지 |

ai/server: `DATASYNC_DATA_DIR`(작업 폴더, 값이 있을 때만 real 모드에서 적재를 받음), `OPENDART_API_KEY`(수집기가 직접 읽음).

## 4. 결정 기록 (#135)

### S1. 시작 신호는 Spring `@Scheduled`

| 후보 | 판단 |
|---|---|
| **(a) Spring `@Scheduled`** (선택) | backend 안 한 곳. 의존성 추가 없음. 중복 확인·이력 기록을 같은 서비스에서 한다 |
| (b) K8s CronJob이 backend의 수동 실행 API 호출 | backend가 여러 대여도 신호는 한 번. 대신 관리자 토큰을 CronJob에 넣어야 하고 시간 설정이 infra로 간다 |
| (c) Jenkins | 실행 화면이 생기지만 도구가 늘고 지금 프로젝트에 없다 |

- (a)의 약점(backend가 여러 대면 각자 신호를 줌)은 S5의 DB 잠금으로 막는다. 그 시각에 backend가 재시작 중이면 그날은 건너뛰므로 관리자 수동 실행을 함께 둔다.
- 기본값은 꺼짐(`-`). 배포 기본 프로필도 `local`이라 프로필로 켜고 끌 수 없어서, 배포에서만 `DATASYNC_CRON`을 넣어 켠다.

### S2. 실행은 ai/server의 작업으로

| 후보 | 판단 |
|---|---|
| **(a) ai/server 작업** (선택) | 업로드와 같은 "시작 → 작업 번호 → 폴링 → 결과 저장"(#63)을 쓴다. backend가 이력·판을 직접 기록한다 |
| (b) K8s CronJob으로 pipelines 이미지 실행 | 자원이 분리되지만, PostgreSQL은 backend만 쓰고(CLAUDE.md 3장) 콜백도 없어서(1장) 결과를 받을 길이 없다 |

- 약점: ai/server가 무거워진다(duckdb, 적재기의 임베딩 모델). #109(파서 실행 환경)와 같은 일이라 함께 푼다.
- ai/server 쪽 연결(`jobs/datasync.py`)은 backend 담당이고, 수집기·적재기 함수를 그대로 부른다. 실행할 때만 불러와서(지연 import) 의존성이 없는 가짜 모드·테스트에는 영향이 없다.

### S3. 실행 상태는 공통 작업(`Job`)으로

| 후보 | 판단 |
|---|---|
| **(a) `Job` 재사용** (선택) | `JobType.DATA_SYNC`를 더하고 `DataSyncRun.jobId`가 작업을 가리킨다(문서 버전과 같은 모양). 상태 전이·폴링·일시 오류 재시도·결과 저장 후 완료·실패 횟수 제한(#63)을 그대로 쓴다 |
| (b) `DataSyncRun`이 ai 작업 번호를 직접 갖고 상태도 스스로 관리 | 이전 ERD("적재는 Job을 쓰지 않는다")와 맞지만, 폴링·재시도·실패 처리를 한 벌 더 만들어야 한다 |

- V14에서 `data_sync_run`의 `status`·`started_at`·`finished_at`을 지우고 `job_id`(고유)·`requested_by`를 더했다. 적재 코드가 없어 쌓인 행이 없었다.
- 화면이 폴링하지 않는 배치라 결과 확인은 S6의 스케줄러가 한다. `Job` 조회 API(`GET /jobs/{id}`)로도 진행을 볼 수 있다.

### S4. 판마다 다른 DuckDB 파일

| 후보 | 판단 |
|---|---|
| **(a) 판마다 다른 파일** `finance-<실행번호>.duckdb` (선택) | 지난 판이 남아 "분석이 어느 판의 숫자로 만들어졌나"를 다시 볼 수 있다. 읽는 쪽은 활성 판의 파일만 열면 된다. `DataVersion.filePath`에 위치를 남긴다 |
| (b) 한 파일을 갈아끼움 | 적재기 기본 동작이라 단순하지만, 지난 판이 사라져 분석이 기록한 판 번호가 빈 이름이 된다 |

- 활성 판은 DB 부분 유일 색인(`uk_data_version_active ... WHERE is_active`)으로 언제나 하나다. 새 판을 켤 때 이전 판 끄기를 먼저 DB에 보낸다(Hibernate는 한 번에 보낼 때 추가를 수정보다 먼저 해서, 그대로 두면 색인에 걸린다).
- 저장 위치는 우선 ai/server 작업 폴더(`DATASYNC_DATA_DIR`)다. 읽는 쪽(mcp-server, ai 영역, 아직 클러스터에 없음)과 같은 파일을 봐야 해서 S3·공유 저장소 등은 ai 담당과 정한다(7장).
- 지난 판 파일 보관 기간은 정하지 않았다(지금은 모두 남음).

### S5. 중복 실행은 DB 잠금으로 막는다

| 후보 | 판단 |
|---|---|
| **(a) PostgreSQL advisory lock** (`pg_advisory_xact_lock`) (선택) | 잠글 행이 없어도(첫 실행) 잠근다. 트랜잭션이 끝나면 풀린다. backend가 여러 대여도 한 줄로 선다 |
| (b) 가장 최근 실행 행을 `FOR UPDATE` | 첫 실행 전에는 잠글 행이 없다 |
| (c) ShedLock 같은 라이브러리 | 의존성과 잠금 테이블이 늘어난다 |

- 잠금 안에서 "가장 최근 실행의 작업이 끝났나"를 보고, 끝나지 않았으면 새로 만들지 않고 그 실행을 돌려준다(관리자 API는 `200`).
- 확인한 근거: 두 스레드가 같은 순간 시작해도 실행이 하나만 생기는 테스트. 잠금 줄을 빼면 이 테스트가 3번 중 3번 실패하는 것을 확인했다.

### S6. 결과 확인도 backend 스케줄러가 한다

- 업로드는 화면이 `GET /jobs/{id}`를 폴링해 결과가 저장되지만, 배치는 물어볼 화면이 없다. 스케줄러가 `PT1M`마다 끝나지 않은 `DATA_SYNC` 작업을 `JobService.getJob`으로 확인한다.
- 일시 오류는 다음 주기에 다시, ai에 없는 작업(404)은 실패, 결과 형식 오류는 실패 횟수로 센다(#63과 같은 규칙).
- 끝나지 않은 작업이 없으면 DB 조회 한 번으로 끝난다.

### S7. 배치 전용 스케줄러 스레드

- `@EnableScheduling`만 켜면 WebSocket 하트비트용 `messageBrokerTaskScheduler`가 유일한 `TaskScheduler`라 배치도 그 스레드에서 돈다. 결과 확인이 길어지면 하트비트가 늦는다.
- `SchedulingConfig`에서 배치 전용 `ThreadPoolTaskScheduler`(2개)를 두었다. 빈이라 종료 때 정리된다.

### S8. 가짜 구현은 적재를 하지 않는다

- backend 가짜 ai(`PythonAiClientStub`)는 적재 시작을 거절하고, ai/server 가짜 모드는 적재 작업을 늘 실패시킨다("가짜 모드에서는 재무 데이터를 적재하지 않습니다.").
- 이유: 가짜 성공을 내면 파일이 없는 판이 활성화되어, 분석이 존재하지 않는 숫자를 가리키게 된다.

### S9. ai/server 실제 모드에서 ai 등록 함수를 필수로 두지 않는다

- 전에는 `PIPELINE_MODE=real`이면 `PIPELINE_ENTRYPOINT`가 필수였다. 그런데 데이터 적재는 ai 등록 함수가 아니라 ai/server가 직접 붙이고, ai의 자료 준비 함수(#134)는 아직 없다. 필수로 두면 적재를 실제 모드로 돌릴 수 없다.
- 그래서 등록 함수는 선택으로 바꾸고, 자료 준비 원본 위치(S3·로컬) 확인은 자료 준비가 등록될 때만 한다. 등록 함수가 없으면 자료 준비·분석·예측은 `501`이다.
- 덕분에 배포에서 `PIPELINE_MODE=real`만 켜도 업로드가 가짜 결과 대신 "아직 지원하지 않는 작업"으로 실패한다.

### S10. 수집기의 `SystemExit`를 잡는다

- 수집기는 API 키가 없으면 `SystemExit`를 던진다(명령줄 도구로 만들어져서). `SystemExit`는 `Exception`이 아니라, 그대로 두면 ai/server 작업 실행기 스레드까지 올라간다.
- ai/server 연결에서 `SystemExit`까지 잡아 "DART API 키가 설정되지 않았습니다."로 바꾼다. ai 담당께는 라이브러리로 쓸 때 일반 예외를 던지도록 제안한다.

### S11. 이미지 모듈 경로에 `pipelines`를 더한다

- 수집기·적재기는 `pipelines` 폴더를 기준으로 서로를 부른다(`from parsers import ...`). 저장소 루트만 모듈 경로에 있으면 `collectors`를 찾지 못한다(로컬에서 확인).
- ai/server `Dockerfile`: `PYTHONPATH=/app:/app/pipelines`.

## 5. 관리자 API

| API | 응답 |
|---|---|
| `POST /api/v1/data-sync-runs` | 새로 시작 `202`, 이미 진행 중 `200`(그 실행). 대상 회사가 없으면 `400 C001` |
| `GET /api/v1/data-sync-runs` | 최근 실행 20건: `{ id, source, requestedBy, createdAt, jobId, status, startedAt, finishedAt, errorMessage, dataVersionId, active }` |

두 API 모두 `ADMIN`만(아니면 `403 A004`). 진행은 `GET /jobs/{jobId}`로도 본다.

## 6. 확인

- `DataSyncServiceTest`(PostgreSQL, 실제 커밋, 10개): 시작(대상 회사·5년 기간을 ai에 보냄, 작업 RUNNING), 진행 중이면 같은 실행, ai 시작 실패 사유와 다음 시작, 내부 사유 감춤, 대상 회사 없음, 성공 시 새 판 켜고 이전 판 끔(활성 하나), 결과에 파일 위치 없음 → 완료 안 함, ai 실패 → 판 유지, **동시 시작 → 실행 하나**, 이력
- `DataSyncControllerTest`(실제 보안 설정): 관리자 `202`/`200`, `USER` `403 A004`, 토큰 없음 `401`, 이력
- `DataSyncSchedulerTest`: 예외를 올리지 않음, cron 설정 이름·서울 시간·기본 꺼짐
- ai/server `test_datasync.py`·`test_registry.py`(가짜 수집기·적재기): 회사마다 수집 → 택소노미 → 적재 순서, 판마다 다른 파일, API 키 없음(`SystemExit`)·수집·적재 실패 문구, 받은 것이 없으면 적재 안 함, 요청 검증(`start` > `end`, 빈 회사 목록 `422`), 실제 모드 등록, 가짜 모드는 적재를 하지 않음
- 로컬 끝까지: PostgreSQL + backend(`AI_SERVICE_MODE=http`) + ai/server(`PIPELINE_MODE=real`, `DATASYNC_DATA_DIR`) → `USER` 시작 `403`, `ADMIN` 시작 `202`, 다시 `200`(같은 실행), 약 3초 뒤 결과 확인 스케줄러가 ai의 실패("DART API 키가 설정되지 않았습니다.")를 받아 이력에 `FAILED`. 실제 DART 호출은 하지 않았다(키 없이, 저장소 `.env`를 읽지 않게 pipelines 복사본으로 실행)

## 7. 알려진 한계와 남은 일

| 문제 | 영향 | 비고 |
|---|---|---|
| ai/server 이미지에 수집·적재 의존성이 없다 | 배포에서 실제 적재를 하면 불러오기에서 실패한다 | duckdb, numpy, lxml, 임베딩 모델(sentence-transformers·torch). #109와 함께 |
| 적재기는 임베딩 모델(`PARSER_EMBED_MODEL`)이 꼭 필요하다 | 모델이 없으면 적재가 실패한다 | 모델 선택·메모리는 ai 담당과 |
| DuckDB 파일 위치가 ai/server 작업 폴더다 | 읽는 쪽(mcp-server)이 다른 서버면 볼 수 없다 | S3·공유 저장소 등 ai 담당과(전달함) |
| 대상 회사 고유번호가 설정에 없다 | 비어 있으면 시작하지 않는다(`400`) | SKT·SKB·KT·LGU+ 고유번호를 확인해 `DATASYNC_CORP_CODES`로 |
| 적재가 오래 걸리면 ai/server 작업 자리 하나를 차지한다 | 그동안 업로드 파싱이 기다릴 수 있다 | 새벽에 돌아 겹치는 일이 드물다. 문제가 되면 전용 자리 |
| 지난 판 파일을 지우지 않는다 | 판마다 파일이 쌓인다 | 보관 기간은 문서 보관 정책과 함께 |
| 원문 XML·리포트 수집은 배치에 없다 | `dart_document.py`·`naver_research.py`는 수동 | 필요하면 같은 방식으로 |
