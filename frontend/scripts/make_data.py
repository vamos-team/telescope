"""로컬 데이터 생성: 저장소 루트 data/demo (manifest.json + DART XML + CSV) + 개념 사전 → frontend/src/local/data.json.

data/는 git에 올리지 않는다. data/demo 자료는 팀 안에서 따로 주고받는다 (frontend/README.md 6장).

파싱은 Python 백엔드 템플릿(_template/backend)의 parse_filing / table_from_csv를 그대로 쓴다.
이 템플릿은 telescope 저장소에 없으므로 경로를 TEMPLATE_BACKEND로 넘긴다 (frontend/README.md 6장).
실행: TEMPLATE_BACKEND=<템플릿>/backend <템플릿>/backend/.venv/bin/python make_data.py
"""
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).parent.parent  # frontend
DATA = HERE.parent / "data" / "demo"  # 저장소 루트 data/demo
if not (DATA / "manifest.json").exists():
    sys.exit(f"자료 폴더를 찾지 못했습니다: {DATA}\n팀에서 공유받은 자료를 저장소 루트 data/demo/에 넣고 다시 실행하세요.")
BACK = Path(os.environ.get("TEMPLATE_BACKEND", HERE.parents[1] / "_template/backend"))
if not (BACK / "app/ingest/dart_xml.py").exists():
    sys.exit(f"Python 백엔드 템플릿을 찾지 못했습니다: {BACK}\n"
             "TEMPLATE_BACKEND에 템플릿의 backend 폴더 경로를 넣어 실행하세요 (frontend/README.md 6장). "
             "화면만 고쳤다면 이 단계 없이 npm run dev로 확인하면 됩니다. docs/frontend/demo.html은 요청이 있을 때만 npm run build:html로 다시 만듭니다.")
sys.path.insert(0, str(BACK))

import yaml  # noqa: E402
from app.ingest.dart_xml import parse_filing  # noqa: E402
from app.ingest.manual import table_from_csv  # noqa: E402
from app.models import Filing  # noqa: E402

man = json.loads((DATA / "manifest.json").read_text(encoding="utf-8"))
tables = []
for stem, alias in man.get("xml", {}).items():
    _, ts = parse_filing(DATA / f"{stem}.xml", company_alias=alias)
    tables += ts
for c in man.get("csv", []):
    f = Filing(rcept_no=c["rcept_no"], corp_name=c["company"], report_name=c.get("report_name", ""), source="IR_PDF")
    tables.append(table_from_csv((DATA / c["file"]).read_text(encoding="utf-8"), filing=f, seq=c["seq"], title=c["title"],
                                 basis=c["basis"], unit=c["unit"], caption=c.get("caption")))

# 개념 사전: KT·SKT 대응을 그대로 쓴다 (숫자만 목업)
concepts = yaml.safe_load((BACK / "app/mapping/concepts.yaml").read_text(encoding="utf-8"))["concepts"]
# LGU+ 목업 CSV는 SKT CSV와 같은 계정명이라 SKT 대응을 복사. 특수관계자 거래 개념은 3사 같은 계정명
for c in concepts:
    if "SKT" in c["members"]:
        c["members"]["LGU+"] = c["members"]["SKT"]
for cid, name, label in [("rp_sales", "특수관계자 매출", "매출 등"), ("rp_purch", "특수관계자 매입", "매입 등"),
                         ("rp_recv", "특수관계자 채권", "채권 등"), ("rp_pay", "특수관계자 채무", "채무 등")]:
    concepts.append({"id": cid, "name": name, "alias": ["특수관계자", "내부거래", "계열사"],
                     "members": {co: [{"label": label, "title": "특수관계자"}] for co in ("SKT", "KT", "LGU+")}})
sources = json.loads((DATA / "sources.json").read_text(encoding="utf-8"))
analysis = json.loads((DATA / "analysis.json").read_text(encoding="utf-8"))

# 상단 바 출처 설명, 예시 불러오기 구성 (data/examples의 카드 a1 a2 b1 a3 b2, 비교 x1 x2와 같음)
# rows/cols가 null이면 전체, compares는 cards 인덱스 묶음, charts는 시각화할 compares 인덱스, docs는 문서 카드, links는 [출발, 도착] (관계 태그 없음) (카드 순서: 표 → 비교 → 문서 → 메모)
subtitle = "SKT·KT·LGU+ 2026년 1분기"
example = {"cards": [{"table_id": "KT_2026Q1:1:1", "rows": None, "cols": [0, 1], "vcol": 0},
                     {"table_id": "KT_2026Q1:2:1", "rows": None, "cols": [0], "vcol": 0},
                     {"table_id": "SKT_2026Q1:1:1", "rows": None, "cols": [0, 1], "vcol": 1},
                     {"table_id": "KT_2026Q1:0:1", "rows": None, "cols": [0], "vcol": 0},
                     {"table_id": "SKT_2026Q1:2:1", "rows": None, "cols": [1], "vcol": 1},
                     {"table_id": "SKT_2026Q1:3:1", "rows": None, "cols": [0, 1], "vcol": 1},
                     {"table_id": "KT_RP_2026Q1:1:1", "rows": None, "cols": [0, 1], "vcol": 1},
                     {"table_id": "LGU_2026Q1:2:1", "rows": None, "cols": [0, 1], "vcol": 1},
                     {"table_id": "LGU_2026Q1:1:1", "rows": None, "cols": [0, 1], "vcol": 1}],
           "compares": [[0, 1, 2, 8], [3, 4], [5, 6, 7]],
           "charts": [2],
           "docs": ["ir-skt-1q26", "int-capex-plan", "news-skt-opi", "ir-skt-aidc"],
           "memos": [{"author": "김센터", "text": "AI DC 감가상각 증가분은 하반기부터 반영될 것 같음. 회수 기간 근거 보강 필요"}],
           "links": [["int-capex-plan", "ir-skt-1q26"], ["news-skt-opi", "SKT_2026Q1:1:1"],
                     ["memo:0", "int-capex-plan"], ["ir-skt-aidc", "int-capex-plan"]]}

(HERE / "src/local/data.json").write_text(json.dumps({"subtitle": subtitle, "example": example,
                                                      "tables": [t.model_dump() for t in tables], "concepts": concepts, "sources": sources, "analysis": analysis},
                                                     ensure_ascii=False), encoding="utf-8")
print(len(tables), "tables:", ", ".join(f"{t.table_id} {t.title}({t.period})" for t in tables))
