# 프런트엔드 API 다이어그램

[API 명세](api-spec.md)의 호출 흐름을 그림으로 정리했다. 경로와 흐름은 명세와 마찬가지로 제안이다. GitHub에서 Mermaid로 그려진다.

## 1. 화면과 API

```mermaid
flowchart LR
  subgraph FE["화면 (Vue)"]
    L[로그인]
    H[페이지 홈 · 새 페이지 창]
    SH[페이지 정보 · 협업자]
    T[헤더 · 왼쪽 사이드바]
    C[캔버스 카드 · 메모 · 다중 선택]
    S[자료 검색 사이드바 · 데이터 입력]
    R[분석 화면]
  end

  subgraph API["backend /api/v1"]
    AU["/auth · /users"]
    CV["/canvases"]
    SHR["/canvases/{id}/shares"]
    EV["/canvases/{id}/events"]
    CMP["/canvases/{id}/compares · card-tags"]
    FL["/filings · /insights · /dashboard"]
    DC["/documents"]
    OUT["/canvases/{id}/analyses<br/>/analyses/{id}/evaluations"]
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
  OUT -- 검토 · 질문 --> AI
  JB -- 상태 조회 --> AI
```

## 2. 로그인, 페이지 홈, 페이지 열기

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend

  U->>F: 사번 · 비밀번호
  F->>B: POST /auth/login
  B-->>F: accessToken, user
  F->>B: GET /canvases?filter=all
  B-->>F: 볼 수 있는 페이지 목록 (myRole, 미리보기)
  Note over F: 페이지 홈 (C00)
  alt 타일 클릭
    U->>F: 페이지 고르기
  else + 새 페이지 (C14)
    F->>B: GET /users?q= (편집할 사람 추천 · 찾기)
    F->>B: POST /canvases (name, quarter, editorIds)
    B-->>F: 새 페이지 (소유자 = 나, 편집할 사람 초대됨)
  end
  F->>B: GET /canvases/{id}
  B-->>F: meta(name, quarter, myRole), cards, links, lastSeq, warnings
  Note over F: myRole이 view면 편집 도구를 숨김. warnings가 있으면 일부 복원 실패 안내
  F->>B: GET /canvases/{id}/card-tags (자료 검색 추천 기준)
  F->>B: GET /canvases/{id}/analyses (내 세션, 최신 세션의 지적 박스)
```

## 2-1. 페이지 정보 · 협업자 (C13)

```mermaid
sequenceDiagram
  actor U as 소유자
  participant F as 페이지 정보
  participant B as backend

  U->>F: 헤더 정보 아이콘
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

  U->>F: 카드 올리기 · 연결 · 값 수정 · 메모(카드 · 메모 카드 · 선) · 표 생성 · 시각화 생성 · 대응 확정/거부
  F->>F: 로컬 상태 반영, 큐에 이벤트 추가
  Note over F: 150ms 동안 묶음
  F->>B: POST /canvases/{id}/events (requestId, events, 글 수정은 baseVersion)
  alt 성공
    B-->>F: lastSeq, firstChangedLayer, textVersions
    F->>B: GET /canvases/{id}/card-tags (자료 검색 추천 기준)
    F->>B: GET /canvases/{id}/compares/{cardId} (비교 · 시각화 카드마다)
  else 409 K003 (다른 사람이 같은 글을 먼저 고침)
    B-->>F: data: 지금 글과 버전
    F->>F: 그 글을 최신으로 바꾸고 안내, 나머지 조작은 새 requestId로 다시 보냄
  else 네트워크 · 5xx
    F->>F: 같은 requestId로 다음에 다시 보냄
  else 그 밖의 4xx (K002 보기 권한 등)
    F->>F: 묶음을 버리고 오류 바 안내
  end
```

## 3-1. 실시간 (다른 사람 변경 · 커서 · 접속자)

서버 연동 모드(`VITE_CANVAS_SYNC=server`)에서만 쓴다. 규약은 `docs/backend/api-spec.md` 5.5·5.6.

```mermaid
sequenceDiagram
  participant A as 화면 (나)
  participant B as backend
  participant C as 화면 (다른 사람)

  A->>B: CONNECT /api/v1/ws (Authorization: Bearer)
  A->>B: SUBSCRIBE /topic/canvases/{id}/events · cursors · presence, /user/queue/canvases
  A->>B: SUBSCRIBE /app/canvases/{id}/presence
  B-->>A: 지금 접속자 목록
  A->>B: POST /canvases/{id}/events (보내지 못한 조작이 있으면 먼저)
  A->>B: GET /canvases/{id}
  B-->>A: cards, links, lastSeq, warnings
  Note over A: 구독 뒤 조회 전에 온 메시지는 모아 두었다가 lastSeq 뒤의 것만 반영
  C->>B: POST /canvases/{id}/events
  B-->>A: (커밋 뒤) firstSeq · lastSeq · events
  alt firstSeq = 내 lastSeq + 1
    A->>A: 화면에 반영 (내 requestId면 번호만 올림)
  else 번호가 빔 · 재접속
    A->>B: GET /canvases/{id} (다시 조회)
  end
  C->>B: SEND /app/canvases/{id}/cursor (50ms마다 최대 1번)
  B-->>A: 커서 (user.id가 나면 무시)
  B-->>A: /user/queue/canvases revoked · deleted → 페이지 홈, role_changed → 편집 도구 맞춤
```

서버 모드는 로그인 화면이 `POST /auth/login`(또는 회원가입 `POST /auth/signup`, 응답 형식 같음)으로 받은 토큰을 저장해 이후 HTTP와 STOMP `CONNECT`에 붙인다. 새로고침은 `GET /users/me`로 복원한다(API 명세 2.1, 2.2, 2.4).

## 4. 자료 검색

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend
  participant A as ai

  rect rgba(128,128,128,0.08)
  Note over U,B: 캔버스 오른쪽 사이드바. 입력 없음: 회사 재무 · DART · 인사이트 탭
  F->>B: GET /dashboard (패널 목록 · 배치)
  F->>B: GET /insights (3사 비교, 인사이트 탭. 화면은 기준 시점을 보내지 않음)
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
  U->>F: Esc (검색어 지우고 탭 화면으로)
  U->>F: 행 고르기
  F->>B: GET /filings/tables/{tableId}
  Note over F: 고른 행 · 열은 캔버스에 올리기로 바로 카드
  end

  rect rgba(128,128,128,0.08)
  Note over U,A: 검색창 + → 데이터 입력 (C16, 서버 모드. 로컬 모드는 화면에서 검증만)
  U->>F: 대상 회사 선택 · 발행일 선택(선택 사항) · 파일 여러 개 선택
  loop 파일마다 한 요청
    F->>B: POST /documents/uploads (file, canvasId, company, publishedDate?)
    alt 업로드 접수
      B->>A: 자료 준비 작업
      B-->>F: 202 jobId, documentIds
      loop 완료까지 2초, RUNNING 1분 뒤 5초
        F->>B: GET /jobs/{jobId}
      end
      alt SUCCEEDED
        F->>B: GET /documents/{documentIds[0]}
        B-->>F: Doc
      else FAILED
        Note over F: 파일 줄에 errorMessage 표시
      end
    else 요청 거절
      B-->>F: 오류 코드와 메시지 (D001이면 data.reason 포함)
    end
  end
  end

  U->>F: 패널 · 항목을 캔버스로 끌어 놓기 (또는 두 번 누르기)
  F->>B: POST /canvases/{id}/events (add_card · add_doc · add_dash · create_compare)
```

## 5. 분석 (검토 → 질문 → 평가)

```mermaid
sequenceDiagram
  actor U as 사용자
  participant F as 화면
  participant B as backend
  participant A as ai

  U->>F: 캔버스 하단 분석하기 (세션이 없을 때) 또는 새로 분석하기 / 업데이트(POST, baseAnalysisId = 같은 세션의 새 버전)
  Note over F: 분석 화면(C20)으로 바꾸고 남은 이벤트(메모 포함)를 먼저 보냄. 다 보내지 못하면 시작하지 않음
  F->>B: POST /canvases/{id}/analyses (asOfDate = 페이지 기준 시점, 형식 미정)
  B->>A: 분석 작업 (컨텍스트 L0~L4: 카드 · 연결 · 메모 3종, 기준 시점 이후 자료는 뺌)
  B-->>F: 202 jobId, analysisId
  loop SUCCEEDED · FAILED까지 (step: REVIEW → QUESTIONS로 프로세스 바 진행)
    F->>B: GET /jobs/{id}
  end
  F->>B: GET /canvases/{id}/analyses
  B-->>F: 세션 (review: 메모 분류 · 주장 · 판정 · findings · insights · note, questions, assumptions)
  Note over F: C21 검토, C10 카드에 지적 박스, C22 질문 리포트. 평가는 회색 대기

  opt 다음 분기 자료가 들어온 뒤 (데모: 버튼)
    U->>F: 다음 분기 자료 반영
    F->>B: POST /analyses/{id}/evaluations
    B-->>F: hit, off, missed, checks, precision, recall
  end

  opt 화면에서만
    U->>F: 정리 글 복사 · Markdown, 질문 리포트 Markdown, 근거 ID → 캔버스 카드
  end
  Note over F: 보고 있는 세션의 canvasSig가 지금과 다르면 "캔버스가 변경되었습니다." 업데이트 · 새로 분석하기
  U->>F: 세션 목록 휴지통
  F->>B: POST /canvases/{id}/analyses/{analysisId}/hide (목록에서 숨김, 결과는 보존)
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
