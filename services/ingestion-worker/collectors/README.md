# collectors

외부 원본을 받아 그대로 저장한다. 파싱·가공은 하지 않는다.

| 파일 | 받는 것 | 저장 위치 |
|---|---|---|
| `dart_xbrl.py` | 정기공시의 재무제표 XBRL zip, DART 택소노미 | `data/raw/dart_xbrl/`, `data/raw/dart_taxonomy/` |
| `dart_document.py` | 정기공시의 원문 XML zip (문장과 모든 표. PDF와 내용이 같음) | `data/raw/dart_document/` |
| `naver_research.py` | 애널리스트 리포트 목록 페이지(HTML)와 상세 링크 | `data/raw/naver_research/` |

## 원문 XML (`dart_document.py`)

OpenDART 공시서류원본파일 API(`document.xml`)로 사업·반기·분기보고서의 원문 zip을 받는다.

```bash
cd pipelines
python -m collectors.dart_document 00159023 --from 20260301 --to 20261130
```

- API 키: 환경변수 또는 저장소 루트 `.env`의 `OPENDART_API_KEY`
- 기간 기본값: 작년 1월 1일 ~ 오늘 (접수일 기준)
- 저장 파일 (공시마다 2개)

  ```
  data/raw/dart_document/<고유번호>/<접수번호>_<보고서코드>.zip    원문 (받은 바이트 그대로)
  data/raw/dart_document/<고유번호>/<접수번호>_<보고서코드>.json   공시 목록 API의 정보
  ```

  보고서코드: `11011` 사업 / `11012` 반기 / `11013` 1분기 / `11014` 3분기

- `.json` 필드: `corp_code`, `rcept_no`, `report_code`, `period`(보고서 이름의 `YYYY.MM`), `report_nm`, `rcept_date`(접수번호 앞 8자리), `source`
- zip 안에는 본문 XML(`<접수번호>.xml`)이 있다. 사업보고서에는 첨부 XML(`<접수번호>_<번호>.xml`, 감사보고서 등)도 들어 있다.
- 정정공시도 모두 받는다. 문서 버전은 새로 쌓고, 어느 버전을 쓸지는 적재 단계에서 정한다.
- 이미 받은 파일은 다시 받지 않는다. zip이 아닌 응답(오류)은 저장하지 않고 `DartError`를 낸다.

## 테스트

```bash
cd pipelines
python -m unittest discover -s collectors/tests -t .   # 가짜 API, 네트워크 없음
```
