# parser

회사·형식과 무관하게 문서를 **제목·문단·표 셀**로 읽는다. 설정 파일 없이 동작한다.

## 파일

| 파일 | 역할 |
|---|---|
| `__init__.py` | `parse()` 진입점 | 
| `loaders.py` | 형식 판별과 형식별 읽기 → 공통 구조(제목·문단·표), 섹션 경로, 쪽·시트, 원본 좌표 |
| `pdf.py` | PDF 읽기(Docling), 쪽·그림 라우팅, 표 구조 검증(머리글 반복, 합계 검산) |
| `vision.py` | 라우팅 규칙, VLM 처리기 인터페이스 |
| `tables.py` | 표 → 셀 목록(행 경로·열 경로), 머리글·라벨 열 판별, 단위·기간·비율·표시 배율 |
| `numbers.py` | 숫자·단위·기간 판별, 한국어 금액 정규화 |
| `model.py` | 데이터 구조 |
| `hints.py` | 단위 힌트(bge 임베딩 제안, 문구 힌트) |
| `requirements.txt` | 패키지와 버전 | 
| `.env.example` | 환경변수 예시 (`HF_TOKEN`) | 
| `tests/` | 단위 테스트 (`unittest`) |

## 설치

```bash
pip install -r requirements.txt
```

파싱만 할 때는 `lxml`·`docling`·`openpyxl`·`python-pptx` 중 읽을 형식의 패키지만 있으면 된다.
PDF를 처음 읽을 때 Docling이 로컬 모델(레이아웃, 표 구조, 그림 종류)을 내려받는다. 약 0.5GB다.

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
| `origin` | `disclosed_code`(코드가 읽음) 또는 `vision_llm`(VLM 처리기가 읽음). `vision_llm` 값은 근거 수치로 쓰지 않는다 |

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

## PDF 라우팅

파일 형식은 `loaders.sniff()`가 판별한다. PDF만 Docling으로 읽고, 다른 형식은 기존 로더를 쓴다.
PDF 안에서는 코드가 경로를 정한다. Docling OCR은 끄고, 글자는 텍스트 층에서만 읽는다.

| 단위 | 조건 | 경로 | 블록 표시(`flags`) |
|---|---|---|---|
| 쪽 | 텍스트 층 글자 15자 이상 | Docling | — |
| 쪽 | 글자 없음 + 내용 그림 있음 | VLM [쪽 옮기기] | `image_page` |
| 쪽 | 글자 없음 + 로고·아이콘뿐 | 없음 | `decor_page` |
| 쪽 | 글자도 그림도 없음 | 없음 | `empty_page` |
| 그림 | 차트·표 + 영역 안 글자 있음 | 텍스트 층 글자를 차트 블록 하나로 | `chart` |
| 그림 | 차트·표 + 영역 안 글자 없음 | VLM [이미지→표] | `chart` |
| 그림 | 로고·아이콘 | 이미지는 버리고 글자만 문단으로 | `picture_text` |
| 그림 | 사진·도식 등 | 글자는 문단으로, VLM [설명 생성] | `picture_text`, `picture` |

- 그림 블록에는 그림 종류도 남는다. 예: `picture:bar_chart`
- VLM 처리기가 없거나 실패하면 그 자리에 `needs_vision` 표시만 남기고 계속 진행한다. 문서 `warnings`에 개수가 남는다.
- Docling이 표를 잘못 읽어도 VLM으로 자동 전환하지 않는다.
- 블록의 `bbox`는 쪽 안 위치다. 단위는 pt이고, 왼쪽 위 기준(왼·위·오른·아래)이다.

표 구조 검증 결과도 `flags`에 남는다.

| 표시 | 뜻 |
|---|---|
| `repeated_header` | 첫 행(머리글)이 표 중간에 다시 나온다. 두 표가 하나로 합쳐졌을 수 있다 |
| `total_mismatch` | 합계·소계 행이 위 항목들의 합과 맞지 않는다. 값이 밀렸을 수 있다 |

## VLM 처리기 연결

```python
from universal_parser import parse, VisionRequest, VisionResult, VisionTask

def my_vlm(req: VisionRequest) -> VisionResult:
    # req.task: VisionTask.PAGE | TO_TABLE | DESCRIBE
    # req.image_png: PNG 바이트, req.page: 쪽, req.bbox: 쪽 안 위치, req.picture_class: 그림 종류
    ...   # 로컬 모델을 부른다. 외부 API로 보내지 않는다
    return VisionResult(paragraphs=[...], tables=[[["구분", "2Q26"], ["매출", "1,234"]]], description="")

res = parse("upload.pdf", vision=my_vlm)
```

- 같은 문서 안에서 같은 이미지·작업은 한 번만 처리한다.
- 이미지 하나당 시간 제한은 기본 600초다(`pdf.load_pdf(vision_timeout=...)`). 넘기면 `warnings`에 남는다.
- 처리기 결과의 블록과 셀에는 `origin="vision_llm"`, 블록 `flags`에 `vision`이 붙는다.

## 테스트

```bash
cd pipelines
python -m unittest discover -s parsers/tests -t .
```

- 테스트 PDF는 PyMuPDF로 만든다. 저장소에 PDF 파일을 두지 않는다.
- 실제 IR PDF 확인은 파일이 있을 때만 돈다: `PARSER_TEST_PDF_DIR=<폴더>` (파일 이름은 `tests/test_ir_pdfs.py`를 본다).
