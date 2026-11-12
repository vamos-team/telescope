"""범용 문서 파서 — 회사·형식과 무관하게 문서를 "제목·문단·표 셀"로 읽는다.

    from universal_parser import parse
    result = parse("report.pdf")
    for t in result["tables"]:
        for c in t["cells"]:
            print(c["row_path"], c["col_path"], c["value"], c["unit"], c["period"], c["src_ref"])

지원 형식: HTML, DART XML, PDF, Excel(.xlsx/.xlsm), CSV, PowerPoint(.pptx), TXT/MD, JSON.
설정 파일 없이 동작한다. 코드가 단위를 못 읽은 표는 힌트로 보완할 수 있다
(suggest_unit_hints: 같은 문서의 비슷한 표에서 bge 임베딩으로 단위를 제안 → parse(unit_hints=...)).
"""
from pathlib import Path
from typing import Callable, Optional

from .hints import clear_hints, set_hints, suggest_unit_hints
from .loaders import load
from .tables import analyze

PARSER_VERSION = "ingest-1.1"
__all__ = ["parse", "load", "analyze", "set_hints", "clear_hints", "suggest_unit_hints", "PARSER_VERSION"]


def parse(path, vision: Optional[Callable[[bytes, int], dict]] = None,
          unit_hints: Optional[dict] = None) -> dict:
    """파일 하나 → {"file_type", "title", "page_count", "warnings", "blocks", "tables"}.

    unit_hints={표 번호: 단위}를 주면, 코드가 단위를 못 찾은 그 표에만 그 단위를 쓴다
    (unit_source="hint", 셀 note="unit_from_hint"). 코드가 찾은 단위는 덮어쓰지 않는다.
    표 번호는 힌트 없이 parse()한 결과의 table_no와 같다. 힌트를 만드는 방법: hints.suggest_unit_hints

    vision(png_bytes, page_no) → {"paragraphs": [...], "tables": [[[칸,…],…],…]} 을 주면
    글자 없는 이미지 쪽(PDF)만 그 함수로 읽고, 그 값에는 origin="vision_llm"이 붙는다.
    """
    path = Path(path)
    doc = load(path, vision=vision)
    blocks, tables = [], []
    for b in doc.blocks:
        tno = None
        if b.kind == "table":
            tno = len(tables)
            hint = (unit_hints or {}).get(tno, "")
            ta = analyze(b.grid, b.context, default_unit=hint)
            hinted = bool(hint) and ta.unit_source == "default"
            if hinted:
                ta.unit_source = "hint"
                ta.warnings = [w for w in ta.warnings if "기본값" not in w] + [f"단위 '{ta.unit}'은 힌트로 채움 — 확인 필요"]
            tables.append({
                "table_no": tno, "section_path": b.section_path, "page": b.page, "sheet": b.sheet,
                "caption": ta.caption, "context": b.context,
                "unit": ta.unit, "unit_source": ta.unit_source, "period_hint": ta.period_hint,
                "period_source": ta.period_source, "header_rows": ta.header_rows,
                "label_cols": ta.label_cols, "header_paths": ta.header_paths,
                "warnings": ta.warnings, "origin": b.origin,
                "cells": [{
                    "r": c.r, "c": c.c, "row_path": c.row_path, "row_label": c.row_label,
                    "col_path": c.col_path, "text": c.text, "value": c.value,
                    "value_base": c.value_base, "unit": c.unit, "period": c.period,
                    "is_total": c.is_total, "src_ref": c.src_ref,
                    "number_format": c.number_format, "display_scale": c.display_scale,
                    "origin": c.origin,
                    "note": c.note or ("unit_from_hint" if hinted and c.unit == ta.unit else "")}
                    for c in ta.cells],
            })
        blocks.append({"kind": b.kind, "level": b.level, "section_path": b.section_path,
                       "text": b.text, "page": b.page, "sheet": b.sheet, "table_no": tno,
                       "flags": b.flags, "origin": b.origin})
    return {"file": str(path), "file_type": doc.file_type, "title": doc.title,
            "page_count": doc.page_count, "warnings": doc.warnings,
            "blocks": blocks, "tables": tables}
