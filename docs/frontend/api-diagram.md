# 프런트엔드 API 다이어그램

[API 명세](api-spec.md)의 호출 흐름을 그림으로 정리했다. 경로와 흐름은 명세와 마찬가지로 제안이다. GitHub에서 Mermaid로 그려진다.

## 1. 화면과 API

```mermaid
flowchart LR
  subgraph FE["화면 (Vue)"]
    L[로그인]
    H[캔버스 홈 · 새 캔버스 창]
    SH[메뉴 팝업 · 공유 창]
    T[상단 바 · 왼쪽 사이드바]
    C[캔버스 카드 · 메모 · 프롬프트]
    S[자료 검색]
    R[오른쪽 결과 패널]
  end

  subgraph API["backend /api/v1"]
    AU["/auth · /users"]
    CV["/canvases"]
    SHR["/canvases/{id}/shares"]
    EV["/canvases/{id}/events"]
    CMP["/canvases/{id}/compares · card-tags"]
    FL["/filings · /insights · /dashboard"]
    DC["/documents"]
    OUT["/canvases/{id}/context · evidence<br/>context-notes · questions · evaluations"]
    JB["/jobs/{id}"]
  end

  AI["ai 서비스<br/>/internal/v1/jobs"]

  L --> AU
  H --> CV
  SH --> CV
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
  OUT -- 정리 글 · 질문 --> AI
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
  B-->>F: 볼 수 있는 캔버스 목록 (myRole, 미리보기)
  Note over F: 캔버스 홈 (C00)
  alt 타일 클릭
    U->>F: 캔버스 고르기
  else + 새 캔버스 (C14)
    F->>B: GET /users?q= (편집할 사람 추천 · 찾기)
    F->>B: POST /canvases (name, quarter, editorIds)
    B-->>F: 새 캔버스 (소유자 = 나, 편집할 사람 초대됨)
  end
  F->>B: GET /canvases/{id}
  B-->>F: meta(myRole), cards, links, lastSeq
  Note over F: myRole이 view면 편집 도구를 숨김
  F->>B: GET /canvases/{id}/card-tags
  Note over F: 오른쪽 결과 패널은 생성하기 전까지 없음
```

## 2-1. 공유 (C13)

```mermaid
sequenceDiagram
  actor U as 소유자
  participant F as 공유 창
  participant B as backend

  U->>F: 로고 → 메뉴 팝업(C15) → 정보 수정
  F->>B: GET /canvases/{id}/shares
  B-->>F: ownerId, members, people
  U->>F: 찾기 칸 클릭
  F->>B: GET /users?q=&excludeCanvas={id}
  B-->>F: 추천 (같은 부서 먼저, 최대 6명)
  U->>F: 사번 · 이름 · 부서 입력
  F->>B: GET /users?q={입력}&excludeCanvas={id}
  B-->>F: 후보 (최대 6명)
  U->>F: 후보 고르기 (편집 가능 · 보기 가능)
  F->>B: PUT /canvases/{id}/shares
  B-->>F: 바뀐 공유 상태
  Note over B: 초대받은 사람의 GET /canvases에 바로 나타남
```

## 3. 카드 조작 (이벤트 묶음 전송)

화면은 로컬 상태를 먼저 바꾸고, 150ms 동안 모은 이벤트를 한 번에 보낸다.

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend

  U->>F: 카드 올리기 · 연결 · 값 수정 · 메모 · 카드 · 선 프롬프트
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

## 4. 자료 검색

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend
  participant A as ai

  rect rgba(128,128,128,0.08)
  Note over U,B: 입력 없음: 대시보드 · 회사 재무 · 인사이트 탭 (Tab)
  F->>B: GET /dashboard (패널 목록 · 배치)
  F->>B: GET /insights?asOfDate= (3사 비교, 인사이트 탭)
  F->>B: GET /documents?kind=news&canvasId= (관련 뉴스 추천, 썸네일 img)
  end

  rect rgba(128,128,128,0.08)
  Note over U,B: 통합 검색 (입력하는 즉시, 150ms 묶음)
  U->>F: 검색어 입력
  par 같은 q로 함께
    F->>B: GET /filings/search?q= (공시 재무 데이터)
  and
    F->>B: GET /documents?kind=ir,ar&q= (IR·AR)
  and
    F->>B: GET /documents?kind=news&q= (뉴스)
  end
  U->>F: 재무 행 · 행 고르기
  F->>B: GET /filings/tables/{tableId}
  Note over F: 고른 행 · 열은 선택 칩에 담음 (아직 이벤트 없음)
  end

  rect rgba(128,128,128,0.08)
  Note over U,A: 입력창 +
  U->>F: 파일 선택
  F->>B: POST /documents/uploads
  B->>A: 자료 준비 작업 시작
  B-->>F: 202 jobId, documentIds
  loop SUCCEEDED · FAILED까지
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /documents/{id}
  Note over F: 선택 칩에 담음
  end

  alt 추가하기 (선택 칩 전부)
    U->>F: 추가하기
  else 길게 눌러 끌기 (그 항목만)
    U->>F: 캔버스에 놓기
  end
  F->>B: POST /canvases/{id}/events (add_card · add_doc · add_dash · create_compare)
```

## 5. 생성하기 (정리 글 → 질문 → 평가)

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend
  participant A as ai

  U->>F: 생성하기
  Note over F: 남은 이벤트(메모 · 프롬프트 포함)를 먼저 보내고 결과 패널을 엶 (접혀 있으면 펼침)

  Note over F: 프로세스 바 1 생성 (캔버스 → 컨텍스트 글)
  F->>B: POST /canvases/{id}/context-notes (asOfDate)
  B->>A: 분석 작업 시작 (컨텍스트 L0~L4: 카드 · 연결 · 메모 · 카드/선 프롬프트)
  B-->>F: 202 jobId
  loop SUCCEEDED · FAILED까지
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /context-notes/{noteId}
  B-->>F: markdown, cites (근거 ID 검증됨)

  Note over F: 프로세스 바 2 질문
  F->>B: GET /canvases/{id}/evidence (근거 ID → 카드 찾기)
  F->>B: POST /canvases/{id}/questions (asOfDate, contextNoteId)
  B->>A: 예측 작업 시작
  B-->>F: 202 jobId
  loop SUCCEEDED · FAILED까지
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /question-sets/{id}

  Note over F: 프로세스 바 3 평가
  F->>B: POST /canvases/{id}/evaluations (questionSetId, canvasId)
  B-->>F: hit, off, missed, checks, precision, recall

  opt 화면에서만
    U->>F: 정리 글 편집 · 복사 · Markdown, 질문 Markdown
    U->>F: 결과 패널 접기 · 펴기
  end
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
