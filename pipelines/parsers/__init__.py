"""범용 문서 파서 — 회사·형식과 무관하게 문서를 "제목·문단·표 셀"로 읽는다.

    from universal_parser import parse
    result = parse("report.pdf")
    for t in result["tables"]:
        for c in t["cells"]:
            print(c["row_path"], c["col_path"], c["value"], c["unit"], c["period"], c["src_ref"])

지원 형식: HTML, DART XML, PDF, Excel(.xlsx/.xlsm), CSV, PowerPoint(.pptx), TXT/MD, JSON.
설정 파일 없이 동작한다. 코드가 단위를 못 읽은 표는 단위 미상(value_base=None)으로 둔다.
"""
from pathlib import Path
from typing import Callable, Optional

from .align import align
from .loaders import load
from .tables import analyze

PARSER_VERSION = "ingest-1.1"
__all__ = ["parse", "load", "analyze", "align", "PARSER_VERSION"]


def parse(path, vision: Optional[Callable[[bytes, int], dict]] = None) -> dict:
    """파일 하나 → {"file_type", "title", "page_count", "warnings", "blocks", "tables"}.

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
            ta = analyze(b.grid, b.context)
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
                    "note": c.note or ""}
                    for c in ta.cells],
            })
        blocks.append({"kind": b.kind, "level": b.level, "section_path": b.section_path,
                       "text": b.text, "page": b.page, "sheet": b.sheet, "table_no": tno,
                       "flags": b.flags, "origin": b.origin})
    return {"file": str(path), "file_type": doc.file_type, "title": doc.title,
            "page_count": doc.page_count, "warnings": doc.warnings,
            "blocks": blocks, "tables": tables}
