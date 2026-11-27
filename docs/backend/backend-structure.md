# Backend 패키지 구조

Gradle 멀티 모듈이다. 루트(`backend/`)에는 공통 빌드 설정만 두고, 코드는 모두 모듈에 둔다.
각 서비스는 base 패키지 아래를 기능(도메인) 단위로 나누고, 각 기능은 동일한 계층 구조를 따른다.

## 모듈 목록

```
backend/
├── common      com.telescope.common    서비스들이 함께 쓰는 응답/예외 처리, 검증, JWT 검증, 보안 설정, 공통 Entity
├── auth        com.telescope.auth      auth 서비스
├── canvas      com.telescope.canvas    canvas 서비스
└── analysis    com.telescope.analysis  analysis 서비스 (Flyway 마이그레이션 포함)
```

## 패키지 목록

```
com.telescope.auth
└── user                    사용자, 로그인과 JWT 발급

com.telescope.canvas
├── canvas                  캔버스, 공유 권한, 편집 이벤트, 실시간 전달
├── global                  canvas 서비스 설정 (WebSocket)
└── infra
    └── auth                auth 서비스 호출

com.telescope.analysis
├── global                  analysis 서비스 설정 (스케줄러)
├── document                문서 자료 관리
├── job                     비동기 작업(자료 준비/분석/예상 질문) 상태 관리
├── datasync                외부 데이터 수집·동기화
├── analysis                문서 분석 결과 관리
├── prediction              예상 질문/답변 관리
├── chat                    채팅
├── bridge                  같은 앱 안에서 기능 사이를 잇는 구현 (auth·canvas 전환 전까지)
└── infra                   외부 시스템 접근 인터페이스
    ├── python              Python(AI) 서버 연동 (docs/backend/ai-contract.md)
    ├── storage             파일 저장소 연동 (docs/backend/file-storage.md)
    └── ai                  LLM 등 AI 연동
```

`global`, `infra`, `bridge`를 제외한 각 기능 패키지는 아래 계층 구조를 동일하게 갖는다.

```
<기능>
├── controller
├── service
├── repository
├── entity
└── dto
```

## 규칙

- Controller는 DTO만 주고받고 Entity를 외부에 노출하지 않는다.
- 기능 간 호출은 Service를 통해서만 한다. 다른 기능의 Repository를 직접 참조하지 않는다.
- 외부 시스템(Python, 파일, LLM)은 `infra`의 인터페이스로만 접근한다. 기능 패키지는 구현체가 아니라 인터페이스에 의존한다.

## 보류

- (해결, #135) 데이터 적재 스케줄러는 Spring `@Scheduled`로 정했다. `datasync.service.DataSyncScheduler`, 설계는 `docs/backend/datasync.md`.
