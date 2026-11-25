# 프런트엔드 API 다이어그램

[API 명세](api-spec.md)의 호출 흐름을 그림으로 정리했다. 경로와 흐름은 명세와 마찬가지로 제안이다. GitHub에서 Mermaid로 그려진다.

## 1. 화면과 API

```mermaid
flowchart LR
  subgraph FE["화면 (Vue)"]
    L[로그인]
    H[캔버스 홈]
    SH[공유 창]
    T[탭 줄 · 왼쪽 사이드바]
    C[캔버스 카드]
    S[자료 추가]
    R[오른쪽 사이드바]
  end

  subgraph API["backend /api/v1"]
    AU["/auth · /users"]
    CV["/canvases"]
    SHR["/canvases/{id}/shares"]
    EV["/canvases/{id}/events"]
    CMP["/canvases/{id}/compares · card-tags"]
    FL["/filings · /insights"]
    DC["/documents"]
    OUT["/canvases/{id}/context · evidence<br/>drafts · questions · evaluations"]
    JB["/jobs/{id}"]
  end

  AI["ai 서비스<br/>/internal/v1/jobs"]

  L --> AU
  H --> CV
  SH --> AU
  SH --> SHR
  T --> CV
  C --> EV
  C --> CMP
  S --> FL
  S --> DC
  R --> OUT
  R --> JB
  S -. 업로드 완료 확인 .-> JB
  DC -- 자료 준비 --> AI
  OUT -- 초안 · 질문 --> AI
  JB -- 상태 조회 --> AI
```

## 2. 로그인, 캔버스 홈, 캔버스 열기

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend

  U->>F: 사번 · 비밀번호
  F->>B: POST /auth/login
  B-->>F: accessToken, user
  F->>B: GET /canvases?filter=all
  B-->>F: 볼 수 있는 캔버스 목록 (myRole, access, 미리보기)
  Note over F: 캔버스 홈 (C00)
  alt 타일 클릭
    U->>F: 캔버스 고르기
  else + 새 캔버스
    F->>B: POST /canvases
    B-->>F: 새 캔버스 (소유자 = 나)
  end
  F->>B: GET /canvases/{id}
  B-->>F: meta(myRole), cards, links, lastSeq
  Note over F: myRole이 view면 편집 도구를 숨김
  F->>B: GET /canvases/{id}/card-tags
  F->>B: GET /canvases/{id}/activities
  F->>B: GET /canvases/{id}/shares
```

## 2-1. 공유 (C13)

```mermaid
sequenceDiagram
  actor U as 소유자 · 편집자
  participant F as 공유 창
  participant B as backend

  U->>F: 상단 바 공유
  F->>B: GET /canvases/{id}/shares
  B-->>F: ownerId, members, access, people
  U->>F: 찾기 칸 클릭
  F->>B: GET /users?q=&excludeCanvas={id}
  B-->>F: 추천 (같은 부서 먼저, 최대 6명)
  U->>F: 사번 · 이름 · 부서 입력
  F->>B: GET /users?q={입력}&excludeCanvas={id}
  B-->>F: 후보 (최대 6명)
  U->>F: 후보 고르기 (편집 가능 · 보기 가능)
  F->>B: PUT /canvases/{id}/shares
  B-->>F: 바뀐 공유 상태
  U->>F: 일반 액세스 바꾸기 (초대한 사람만 · 회사 전체 보기)
  F->>B: PUT /canvases/{id}/shares
  Note over B: 초대받은 사람의 GET /canvases에 바로 나타남
```

## 3. 카드 조작 (이벤트 묶음 전송)

화면은 로컬 상태를 먼저 바꾸고, 150ms 동안 모은 이벤트를 한 번에 보낸다.

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend

  U->>F: 카드 올리기 · 연결 · 값 수정
  F->>F: 로컬 상태 반영, 큐에 이벤트 추가
  Note over F: 150ms 동안 묶음
  F->>B: POST /canvases/{id}/events
  alt 성공
    B-->>F: lastSeq, firstChangedLayer
    F->>B: GET /canvases/{id}/card-tags
    F->>B: GET /canvases/{id}/compares/{cardId} (비교 카드마다)
  else 실패
    B-->>F: error
    F->>F: 묶음을 큐 앞에 되돌림, 안내 문구
  end
```

## 4. 자료 추가

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend
  participant A as ai

  rect rgba(128,128,128,0.08)
  Note over U,B: 공시 탭
  U->>F: 질문 입력
  F->>B: GET /filings/search?q=
  F->>B: GET /insights?q=
  U->>F: 행 고르기
  F->>B: GET /filings/tables/{tableId}
  F->>B: POST /canvases/{id}/events (add_card)
  end

  rect rgba(128,128,128,0.08)
  Note over U,B: IR·뉴스 탭
  F->>B: GET /documents?kind=ir,news&canvasId= (검색어 없으면 추천)
  U->>F: 카드를 캔버스로 끌기
  F->>B: POST /canvases/{id}/events (add_doc)
  end

  rect rgba(128,128,128,0.08)
  Note over U,A: 사내 자료 탭
  U->>F: 파일 끌어 놓기
  F->>B: POST /documents/uploads
  B->>A: 자료 준비 작업 시작
  B-->>F: 202 jobId, documentIds
  loop SUCCEEDED · FAILED까지
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /documents/{id}
  F->>B: POST /canvases/{id}/events (add_doc)
  end
```

## 5. 프롬프트 · 초안 · 질문 · 평가

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend
  participant A as ai

  F->>B: GET /deliverables
  Note over F: 탭마다 실행 전에 남은 이벤트를 먼저 보냄

  U->>F: 프롬프트 추출
  F->>B: POST /canvases/{id}/context
  B-->>F: layers L0~L4, hashes, messages

  U->>F: 초안 생성
  F->>B: POST /canvases/{id}/drafts (deliverableId, asOfDate)
  B->>A: 분석 작업 시작
  B-->>F: 202 jobId
  loop SUCCEEDED · FAILED까지
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /drafts/{draftId}
  F->>B: GET /canvases/{id}/evidence (근거 ID → 카드 찾기)

  U->>F: 질문 생성
  F->>B: POST /canvases/{id}/questions (asOfDate)
  B->>A: 예측 작업 시작
  B-->>F: 202 jobId
  loop SUCCEEDED · FAILED까지
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /question-sets/{id}

  U->>F: 평가
  F->>B: POST /canvases/{id}/evaluations (questionSetId, draftId)
  B-->>F: hit, off, missed, checks, precision, recall
```

## 6. 작업 상태 (job)

`docs/backend/job.md`의 상태 값을 따른다.

```mermaid
stateDiagram-v2
  [*] --> QUEUED: POST (202 jobId)
  QUEUED --> RUNNING
  RUNNING --> SUCCEEDED: 결과 조회 API 호출
  RUNNING --> FAILED: errorMessage 표시
  QUEUED --> FAILED
  SUCCEEDED --> [*]
  FAILED --> [*]
```
