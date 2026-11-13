# backend 작업 규칙

> 루트 `CLAUDE.md`의 규칙에 더해 적용된다. 이 폴더 안에서만 작업한다.

## 1. 기술 스택

- Java 21, Spring Boot, Gradle 단일 모듈, JPA, Flyway, MySQL
- base 패키지: `com.telescope.backend`
- 기존 `spring-boot-starter-webmvc`와 Spring Boot 버전은 임의로 바꾸지 않는다.
- Boot가 버전을 관리하는 의존성은 버전을 적지 않는다. 관리 범위 밖이면 버전을 고정한다.
- Spring AI는 `chat` 기능에서만 쓴다. 버전은 Boot와의 호환을 확인한 뒤 확정한다.

## 2. 패키지 구조

`com.telescope.backend` 아래:

| 패키지 | 책임 |
|---|---|
| `global` | 공통 설정, 예외, 응답 포맷, 공통 Entity |
| `document` | 문서 업로드, 버전, 발행일·출처, 자료 준비 상태 |
| `job` | 비동기 작업 공통 (상태 전이, 폴링 대상) |
| `datasync` | 재무 데이터 적재 실행 이력, 활성 데이터 버전 |
| `analysis` | 분석 실행, 허용 문서 스냅샷, 근거 점검 결과 |
| `prediction` | 예상 질문 실행과 결과 |
| `chat` | 대화와 메시지, Spring AI 오케스트레이션 |
| `infra` | 외부 연동: `python`(ai 서비스 호출), `storage`(파일), `ai`(ChatClient, MCP Client 설정) |

> 예측 평가 기능의 패키지는 아직 만들지 않았다. 패키지가 추가되면 이 표도 갱신한다.

- `global`, `infra`를 제외한 기능 패키지 안은 `controller / service / repository / entity / dto`로 나눈다.
- 클래스 이름: `XxxController`, `XxxService`, `XxxRepository`, `Xxx`(Entity), `XxxRequest`, `XxxResponse`

## 3. 계층 규칙

- Controller는 요청 검증과 응답 변환만 한다. 비즈니스 로직을 두지 않는다.
- Controller는 DTO만 주고받는다. **Entity를 응답이나 요청에 쓰지 않는다.**
- 기능 간 호출은 Service를 통해서만 한다. 다른 기능의 Repository를 직접 쓰지 않는다.
- 외부 시스템(ai 서비스, 파일, LLM)은 `infra`의 인터페이스로만 접근한다. 기능 패키지는 구현체가 아니라 인터페이스에 의존한다.
- 분석·예측·ML 로직을 backend에 구현하지 않는다. ai 서비스를 `PythonAiClient`로 호출하고 결과를 저장·조회한다.
- DuckDB, ChromaDB를 직접 열거나 의존성을 추가하지 않는다.

## 4. 코드 작성 규칙

**DTO**
- Request/Response DTO는 Java `record`로 작성한다.
- 요청 검증은 DTO의 Bean Validation 애노테이션으로 한다.
- Entity ↔ DTO 변환은 DTO의 정적 팩토리(`from`) 또는 Service에서 한다. Controller에서 하지 않는다.

**Entity**
- `@Data`, `@Setter`를 쓰지 않는다. 상태 변경은 의미 있는 메서드(`start()`, `succeed()` 등)로 한다.
- 기본 생성자는 `protected`로 둔다. 생성은 정적 팩토리 또는 빌더로 한다.
- 모든 Entity는 `BaseTimeEntity`를 상속한다.
- `@Enumerated(EnumType.STRING)`만 쓴다. ORDINAL을 쓰지 않는다.
- 연관관계는 `LAZY`를 기본으로 한다. 양방향은 꼭 필요할 때만 둔다.
- Entity의 `equals/hashCode`에 연관 Entity를 넣지 않는다.

**Service와 트랜잭션**
- 트랜잭션은 Service 메서드에 둔다. 조회 메서드는 `@Transactional(readOnly = true)`.
- 목록 조회에서 N+1이 생기지 않게 fetch join이나 DTO 프로젝션을 쓴다.
- `spring.jpa.open-in-view`는 `false`로 유지한다.
- 외부 호출(ai 서비스, 파일 I/O)을 DB 트랜잭션 안에서 길게 잡지 않는다.

**예외와 응답**
- 모든 응답은 `ApiResponse`로 감싼다. 실패는 `BusinessException`과 `ErrorCode`로 던지고 `GlobalExceptionHandler`가 변환한다.
- API 경로는 `/api/v1/...`, 명사 복수형 REST 규칙을 따른다.
- 오래 걸리는 작업은 `202`와 `jobId`를 반환한다. 클라이언트가 `GET /jobs/{id}`로 조회한다.

**기타**
- 로그에 비밀 값과 문서 본문 전체를 남기지 않는다.
- 설정 값(경로, URL, 모델명)은 코드에 박지 않고 `application*.yml`과 환경변수로 둔다.
- 주석은 "왜"가 필요한 곳에만 쓴다.

## 5. DB와 스키마

- DB는 MySQL이다. PostgreSQL이나 H2로 바꾸지 않는다.
- 스키마 변경은 Flyway로만 한다. `db/migration/V<번호>__<설명>.sql`
- JPA 설정은 `ddl-auto: validate`를 유지한다. `update`, `create`를 쓰지 않는다.
- **이미 적용된 마이그레이션 파일은 수정하지 않는다.** 변경은 새 번호의 파일을 추가한다.
- 마이그레이션 번호가 겹치지 않게 새 파일을 만들기 전에 현재 최신 번호를 확인한다.
- DB 접속 정보는 환경변수(`MYSQL_*`)로 주입한다. 기본값에 비밀번호를 넣지 않는다.

## 6. 도메인 규칙

- **문서 버전과 분석·예측·평가 결과는 수정하지 않는다.** 바뀌면 새 버전·새 실행을 만든다.
- 분석을 시작할 때 사용한 문서 버전을 `AnalysisRunDocument`로 **스냅샷**해서 남긴다.
- 예측과 평가는 `as_of_date`가 필수다. 기준 시점 이후에 발행된 문서는 허용 문서에서 서버가 제외한다.
- 허용 문서와 기준 시점은 LLM이 인자로 정하지 않는다. 서버가 강제로 주입한다. (Spring AI 도구 호출 방식의 구체적 구현은 PoC 후 확정)
- 분석 요청 때 사용자가 입력한 검토 기준 컨텍스트(`userInput`)는 분석 실행과 함께 저장하고 수정하지 않는다. 팀 단위 프로필 설정은 MVP에서 제외한다.
- 자료 준비(ingest)가 끝나지 않은 문서 버전은 분석 허용 문서에 넣지 않는다.
- Job 상태는 `QUEUED → RUNNING → SUCCEEDED/FAILED`로만 전이한다. 역방향 전이를 막는다.

## 7. 하지 않는 것

- 사내 SSO 연동은 만들지 않는다. 인증은 JWT로만 구현한다. (SSO는 이후 확장 과제)
- 권한 검사를 클라이언트에 맡기지 않는다. 공용 문서 수집·삭제는 관리자만, 분석·예측 결과는 요청한 본인만 조회하도록 서버가 강제한다.
- 콜백 수신 엔드포인트를 만들지 않는다. 작업 상태는 폴링으로 조회한다.
- ML·분석 로직, DuckDB·ChromaDB 접근을 구현하지 않는다.
- `build.gradle`과 `application*.yml`의 큰 변경은 이슈에 명시된 경우에만 한다.

## 8. 테스트와 확인

- 변경 후 `./gradlew build`로 빌드와 테스트를 확인한다.
- 기동 확인은 `/actuator/health`가 200인지로 한다.
- 새 기능에는 테스트를 함께 작성한다. Service는 단위 테스트, Repository는 JPA 슬라이스 테스트로 한다.
- 외부 연동은 `Stub` 구현으로 테스트한다. 실제 ai 서비스에 의존하는 테스트를 만들지 않는다.
- 테스트용 DB 구성 방식은 팀 결정 전까지 임의로 정하지 않는다. (확인 필요)