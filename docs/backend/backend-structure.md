# Backend 패키지 구조

`com.telescope.backend` 아래를 기능(도메인) 단위로 나누고, 각 기능은 동일한 계층 구조를 따른다.

## 패키지 목록

```
com.telescope.backend
├── global                 공통 응답/예외 처리, 공통 Entity 등 모든 기능이 공유하는 요소
├── document                문서 자료 관리
├── job                     비동기 작업(자료 준비/분석/예상 질문) 상태 관리
├── datasync                외부 데이터 수집·동기화
├── analysis                문서 분석 결과 관리
├── prediction              예상 질문/답변 관리
├── chat                    채팅
└── infra                   외부 시스템 접근 인터페이스
    ├── python              Python(AI) 서버 연동
    ├── storage             파일 저장소 연동
    └── ai                  LLM 등 AI 연동
```

`global`, `infra`를 제외한 각 기능 패키지는 아래 계층 구조를 동일하게 갖는다.

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

- `datasync.scheduler`: 트리거 주체(Jenkins 또는 Spring)가 정해질 때까지 생성하지 않는다.
