# Telescope 화면설계

분석 Agent와 Chat Agent의 현재 화면설계와 데모를 `docs/frontend/`에서 관리합니다. 화면설계 작업 이슈는 #55이며, 기존 `docs/#49-final-screen-design` 브랜치를 재사용합니다.

- 분석하기 상단에서 `자료 검토 | 인사이트`를 전환합니다.
- 회사 재무는 사이드바 DART 대시보드(A10)에서 보여 주며, 자산 계정은 그 안의 탭입니다.
- Stella 챗봇은 근거 탐색(연보라)과 인사이트(크림 노랑) 모드로 구분합니다.

- [IA 및 화면별 기능·흐름](demo/ia.md)
- [IA 최종 이미지](IA.png)
- [HTML 데모](demo/index.html): 내려받아 브라우저에서 열 수 있습니다.
- [재무 데이터](demo/data/financials.json)
- [뉴스 목록](demo/data/news.json)
- [부서별 검토 포인트](demo/data/departments.md): 검토 기준 작성 참고 자료

최종 화면설계에 따른 기획 문서 수정은 #28의 `docs/planning/`에서 관리합니다. IA에는 현재 설계, 데모의 구현 범위와 확정이 필요한 항목을 구분해 기록합니다. Figma 화면과의 일치 검증은 별도 진행 대상입니다.
