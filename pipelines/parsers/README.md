# parser

회사·형식과 무관하게 문서를 **제목·문단·표 셀**로 읽는다. 설정 파일 없이 동작한다.

## 파일

| 파일 | 역할 |
|---|---|
| `__init__.py` | `parse()` 진입점 | 
| `loaders.py` | 형식별 읽기 → 공통 구조(제목·문단·표), 섹션 경로, 쪽·시트, 원본 좌표 |
| `tables.py` | 표 → 셀 목록(행 경로·열 경로), 머리글·라벨 열 판별, 단위·기간·비율·표시 배율 |
| `numbers.py` | 숫자·단위·기간 판별, 한국어 금액 정규화 |
| `model.py` | 데이터 구조 |
| `hints.py` | 단위 힌트(bge 임베딩 제안, 문구 힌트) |
| `requirements.txt` | 패키지와 버전 | 
| `.env.example` | 환경변수 예시 (`HF_TOKEN`) | 

## 설치

```bash
pip install -r requirements.txt
```

파싱만 할 때는 `lxml`·`pdfplumber`·`openpyxl`·`python-pptx` 중 읽을 형식의 패키지만 있으면 된다.

## 사용

```python
from universal_parser import parse

res = parse("어떤_회사_사업보고서.html")      # pdf / xlsx / pptx / csv / json / txt 도 같음
for t in res["tables"]:
    print(t["section_path"], t["unit"], t["period_hint"])
    for c in t["cells"]:
        # 행 경로 × 열 경로 → 값. value_base는 원 단위 환산값(단위 미상이면 None)
        print(c["row_path"], c["col_path"], c["value"], c["unit"], c["period"], c["src_ref"])
```

지원 형식: HTML, DART XML, PDF, Excel(.xlsx/.xlsm), CSV, PowerPoint(.pptx), TXT/MD, JSON.

## 셀에 담기는 것

| 키 | 뜻 |
|---|---|
| `row_path`, `col_path` | 행 라벨 경로와 열 머리글 경로 (표가 뒤집혀 있어도 같은 형태) |
| `value` | 원본 칸의 숫자 (괄호·△ 음수 처리) |
| `unit` | 단위. 원화(`원`~`조원`), `%`, 또는 원화 아닌 단위(`주`·`명`·`수량`·`tCO2-eQ`·`사` 등) |
| `value_base` | 원화 금액이면 원 단위 환산값. 원화 아닌 단위면 원래 값 그대로. 단위 미상이면 `None` |
| `period` | `2025Q3`, `current`(당기), `prior`(전기), 연도 등 |
| `src_ref` | 원본 좌표. Excel은 `시트!D14`, 그 외는 표 안 `R6C9` |
| `note` | 판단 근거: `unit_missing`, `unit_from_hint`, `quantity_label`, `unit_inherited_rows`, `ratio_label`, `pct_format`, `ratio_fraction_assumed`, `display_scale_mixed` |
| `origin` | `disclosed_code`(코드가 읽음) 또는 `vision_llm`(vision 함수가 읽음) |

- 원화 금액인지는 `universal_parser.numbers.is_money_unit(unit)`으로 구분한다.
- `(단위: 개, 백만원)`처럼 단위가 여럿 적힌 표는 어느 열이 어느 단위인지 알 수 없어 단위 미상으로 둔다.
- 단위를 못 찾으면 원 단위로 가정하지 않고 `value_base=None`, `note="unit_missing"`으로 둔다.

## bge 임베딩으로 단위 힌트 붙이기

```python
from universal_parser import parse, suggest_unit_hints

res = parse("report.html")
proposals = suggest_unit_hints(res)        # 단위 미상 표마다 단위를 제안
hints = {p["table_no"]: p["unit"] for p in proposals if p["unit"]}     # 검토 후 고르는 것을 권장
res2 = parse("report.html", unit_hints=hints)   # 그 표들만 채워짐: unit_source="hint", note="unit_from_hint"
```
