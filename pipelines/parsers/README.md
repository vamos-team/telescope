# parser

회사·형식과 무관하게 문서를 **제목·문단·표 셀**로 읽는다. 설정 파일 없이 동작한다.

## 파일

| 파일 | 역할 |
|---|---|
| `__init__.py` | `parse()` 진입점 | 
| `loaders.py` | 형식 판별과 형식별 읽기 → 공통 구조(제목·문단·표), 섹션 경로, 쪽·시트, 원본 좌표 |
| `pdf.py` | PDF 읽기(Docling), 쪽·그림 라우팅, 표 구조 검증(머리글 반복, 합계 검산) |
| `vision.py` | 라우팅 규칙, VLM 처리기 인터페이스 |
| `markdown.py` | 파서 결과 → LLM용 마크다운, 장·절 단위 나누기 |
| `tables.py` | 표 → 셀 목록(행 경로·열 경로), 머리글·라벨 열 판별, 단위·기간·비율·표시 배율 |
| `numbers.py` | 숫자·단위·기간 판별, 한국어 금액 정규화 |
| `model.py` | 데이터 구조 |
| `align.py` | 항목 매칭: 이름이 달라도 같은 뜻인 항목 찾기 (`align()`) |
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

`cells`에는 숫자 칸만 들어간다. 표의 원문 격자(머리글·행 이름 포함)는 `t["rows"]`(글자 2차원 목록)에 있다.

## LLM용 마크다운

```python
from parsers import parse, to_markdown, split_markdown

res = parse("report.pdf")
md = to_markdown(res)                          # 문서 전체
chunks = split_markdown(res, max_chars=4000)   # [{"index", "text", "chars", "section_paths", "page_start", "page_end", "sheets"}]
```

마크다운은 LLM이 문서의 맥락(아젠다, 설명)을 읽는 용도다. 숫자 계산은 `tables`의 칸 데이터나 재무 DB로 하고, 마크다운 속 숫자를 그대로 계산에 쓰지 않는다.

| 원본 | 마크다운 |
|---|---|
| 쪽(PDF·PPTX), 시트(Excel) | 바뀔 때 한 줄: `[p.13]`, `[시트: 손익계산서]` |
| 제목 | `#` 수준 유지 (문서 제목이 `#`이라 한 단계 내림) |
| 표 | 마크다운 표. 위에 `단위: … · 기간: …`(코드가 읽은 값, 변환 없음). 여러 줄 머리글은 열마다 ` / `로 합침 |
| 표의 제목·주석·단위 행 (행 전체에 같은 글자) | 표 위 줄로 뺌 |
| 병합 칸 복제 | 옆 칸과 같은 행 이름·머리글은 비움. 숫자는 같아도 그대로 |
| 모든 행이 빈 열 | 뺌 |
| VLM이 읽은 내용 (`origin="vision_llm"`) | `> [VLM 판독, 확인 필요] …` |
| 구조 오류 표시 (`repeated_header` 등) | 표 위 `> ⚠ 구조 확인 필요: …` |
| `needs_vision` | `> [읽지 못한 쪽/차트/그림: 이미지에만 있는 내용, VLM 처리 안 됨]` |
| 차트 안 글자 (`chart`) | `[차트 안 글자, 순서가 섞일 수 있음] …` |
| 그림 안 글자 (`picture_text`) | 그림마다 한 줄: `[그림 안 글자] a / b` |
| `empty_page`, `decor_page` | 뺌 |
| 문서 `warnings` | 끝에 `## 파싱 경고` 목록 |

나누기(`split_markdown`):

- 장·절(`section_path` 앞 두 단계)이 바뀌면 새 조각을 시작한다. 지금 조각이 `max_chars`의 1/4보다 짧으면 다음 절과 합친다.
- 조각 길이는 `max_chars`(기본 4,000자) 이하다. 블록 경계에서 나눈다. 긴 문단은 문장 경계에서 나눈다.
- 긴 표는 행 단위로 나누고, 조각마다 표 위 줄과 머리글을 반복한다(`(표 계속 2/3)`).
- 머리글 줄이 `max_chars`의 절반보다 긴 표는 열 묶음으로도 나눈다. 행 이름 열은 묶음마다 반복한다.
  머리글 줄이 1,000자를 넘는 표는 모든 값 열에 공통인 위쪽 머리글을 `열 머리글 공통: …` 한 줄로 뺀다.
- 조각 첫 줄: `[문서: 파일 이름 · 위치: 장 > 절 · p.3–5]`.
- 측정(DART 사업·반기·분기보고서 XML 5개, Excel 3개, IR PDF 3개): 4,000자 초과 조각 0개, 조각 사이에서 빠진 표 숫자 0개.

## 셀에 담기는 것

| 키 | 뜻 |
|---|---|
| `row_path`, `col_path` | 행 라벨 경로와 열 머리글 경로 (표가 뒤집혀 있어도 같은 형태) |
| `value` | 원본 칸의 숫자 (괄호·△ 음수 처리) |
| `unit` | 단위. 원화(`원`~`조원`), `%`, 또는 원화 아닌 단위(`주`·`명`·`수량`·`tCO2-eQ`·`사` 등) |
| `value_base` | 원화 금액이면 원 단위 환산값. 원화 아닌 단위면 원래 값 그대로. 단위 미상이면 `None` |
| `period` | `2025Q3`, `current`(당기), `prior`(전기), 연도 등 |
| `src_ref` | 원본 좌표. Excel은 `시트!D14`, 그 외는 표 안 `R6C9` |
| `note` | 판단 근거: `unit_missing`, `quantity_label`, `unit_inherited_rows`, `ratio_label`, `pct_format`, `ratio_fraction_assumed`, `display_scale_mixed` |
| `origin` | `disclosed_code`(코드가 읽음) 또는 `vision_llm`(VLM 처리기가 읽음). `vision_llm` 값은 근거 수치로 쓰지 않는다 |

- 원화 금액인지는 `universal_parser.numbers.is_money_unit(unit)`으로 구분한다.
- `(단위: 개, 백만원)`처럼 단위가 여럿 적힌 표는 어느 열이 어느 단위인지 알 수 없어 단위 미상으로 둔다.
- 단위를 못 찾으면 원 단위로 가정하지 않고 `value_base=None`, `note="unit_missing"`으로 둔다.

## PDF 라우팅

파일 형식은 `loaders.sniff()`가 판별한다. PDF만 Docling으로 읽고, 다른 형식은 기존 로더를 쓴다.
PDF 안에서는 코드가 경로를 정한다. Docling OCR은 끄고, 글자는 텍스트 층에서만 읽는다.

| 단위 | 조건 | 경로 | 블록 표시(`flags`) |
|---|---|---|---|
| 쪽 | 텍스트 층 글자 15자 이상 | Docling | — |
| 쪽 | 글자 없음 + 읽지 못한 내용 보임 (스캔한 글자·표) | VLM [쪽 옮기기] | `image_page` |
| 쪽 | 글자 없음 + 내용 그림 있음 | VLM [쪽 옮기기] | `image_page` |
| 쪽 | 글자 없음 + 로고·아이콘뿐 | 없음 | `decor_page` |
| 쪽 | 글자도 그림도 없음 (색 배경 간지 포함) | 없음 | `empty_page` |
| 그림 | 차트·표 + 영역 안에 값 숫자 있음 | 텍스트 층 글자를 차트 블록 하나로 | `chart` |
| 그림 | 차트·표 + 영역 안에 값 숫자 없음 (단위·범례·기간 글자만 있거나 글자 없음) | 글자는 문단으로, VLM [차트→표] | `picture_text`, `chart` |
| 그림 | 로고·아이콘 | 이미지는 버리고 글자만 문단으로 | `picture_text` |
| 그림 | 사진·도식 등 | 글자는 문단으로, VLM [설명 생성] | `picture_text`, `picture` |

- "읽지 못한 내용 보임": Docling은 OCR 없이 읽지 못한 글자 영역을 결과에서 뺀다. 그래서 글자가 15자 미만인 쪽은
  쪽 이미지에서 텍스트 층 글자와 로고·아이콘 영역을 가린 뒤 윤곽(이웃 픽셀 밝기 차) 비율을 잰다.
  0.5% 이상이거나 칸 글자가 없는 표가 있으면 읽지 못한 내용으로 본다(`vision.MIN_PAGE_EDGES`).
  측정값: 간지·로고 쪽 0.0~0.2%, 스캔한 내용 쪽 3.3~17.8%.
- "값 숫자": 기간(`2Q25`, `1H26`), 연도(`2025`, `2025년`), 월(`3월`)을 뺀 숫자 (`vision.has_value_numbers`).
- 그림 블록에는 그림 종류도 남는다. 예: `picture:bar_chart`
- VLM 처리기가 없거나 실패하면 그 자리에 `needs_vision` 표시만 남기고 계속 진행한다. 문서 `warnings`에 개수가 남는다.
- Docling이 표를 잘못 읽어도 VLM으로 자동 전환하지 않는다.
- 블록의 `bbox`는 쪽 안 위치다. 단위는 pt이고, 왼쪽 위 기준(왼·위·오른·아래)이다.

표 구조 검증 결과도 `flags`에 남는다.

| 표시 | 뜻 |
|---|---|
| `repeated_header` | 첫 행(머리글)이 표 중간에 다시 나온다. 두 표가 하나로 합쳐졌을 수 있다 |
| `total_mismatch` | 합계·소계 행이 위 항목들의 합과 맞지 않는다. 값이 밀렸을 수 있다 |

VLM이 만든 표(`origin="vision_llm"`)에는 아래 표시가 더 붙을 수 있다. 표는 버리지 않는다.

| 표시 | 뜻 |
|---|---|
| `growth_mismatch` | 증감률(QoQ, YoY) 칸이 값 칸으로 다시 계산한 증감률과 맞지 않는다. 숫자가 다른 칸에 들어갔을 수 있다. 표시값의 반올림 범위는 허용한다 |
| `vision_ragged` | 행마다 칸 수가 다르다 |
| `vision_bad_number` | 숫자·기호로만 된 칸이 숫자로 읽히지 않는다 (예: `1,23.4.5`) |

## VLM 처리기 연결

parsers는 네트워크에 접근하지 않는다. 처리기는 밖에서 만들어 넘긴다. OpenAI 처리기는 `pipelines/vision`에 있다.

```python
from parsers import parse
from vision import from_env

res = parse("upload.pdf", vision=from_env())   # OPENAI_API_KEY가 없으면 None → needs_vision 표시만
```

다른 처리기를 쓰려면 `VisionRequest`를 받아 `VisionResult`를 돌려주는 함수를 넘긴다.

```python
from parsers import parse, VisionRequest, VisionResult, VisionTask

def my_vlm(req: VisionRequest) -> VisionResult:
    # req.task: VisionTask.PAGE | TO_TABLE | DESCRIBE
    # req.image_png: PNG 바이트, req.page: 쪽, req.bbox: 쪽 안 위치, req.picture_class: 그림 종류
    return VisionResult(paragraphs=[...], tables=[[["구분", "2Q26"], ["매출", "1,234"]]], description="")

res = parse("upload.pdf", vision=my_vlm)
```

- 같은 문서 안에서 같은 이미지·작업은 한 번만 처리한다.
- 문서 하나에서 처리기는 최대 30번 부른다(`vision.VISION_MAX_CALLS`, `pdf.load_pdf(vision_max_calls=...)`).
  넘는 영역은 `needs_vision`으로 남고 `warnings`에 개수가 남는다. 같은 이미지 재사용은 세지 않는다.
- 이미지 하나당 시간 제한은 기본 600초다(`pdf.load_pdf(vision_timeout=...)`). 넘기면 `warnings`에 남는다.
- 처리기가 예외를 던지면 그 영역은 `needs_vision`으로 남고 `warnings`에 이유가 남는다.
- 처리기 결과의 블록과 셀에는 `origin="vision_llm"`, 블록 `flags`에 `vision`이 붙는다. 이 값은 근거 수치로 쓰지 않는다.

## 테스트

```bash
cd pipelines
python -m unittest discover -s parsers/tests -t .
```

- 테스트 PDF는 PyMuPDF로 만든다. 저장소에 PDF 파일을 두지 않는다.
- 실제 IR PDF 확인은 파일이 있을 때만 돈다: `PARSER_TEST_PDF_DIR=<폴더>` (파일 이름은 `tests/test_ir_pdfs.py`를 본다).
- 큰 DART 문서 나누기 확인은 파일이 있을 때만 돈다: `PARSER_TEST_DART_XML=<사업보고서 XML 경로>`.
- OpenAI 처리기 테스트: `python -m unittest discover -s vision/tests -t .` (가짜 클라이언트, 네트워크 없음)
- 실제 API 호출 테스트는 비용이 들어서 켤 때만 돈다: `OPENAI_API_KEY=... VISION_LIVE_TEST=1`. 테스트가 그린 가짜 차트만 보낸다.
