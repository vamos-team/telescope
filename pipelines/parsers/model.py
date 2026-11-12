"""공통 문서 구조 — 모든 로더의 출력 형식."""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class RawCell:
    """로더가 만든 표의 한 칸 (병합 해제 후)."""
    text: str
    is_header: bool = False          # 원본이 머리글로 표시(TH/THEAD, 굵은 글씨 등)
    number: Optional[float] = None   # Excel처럼 원본이 숫자 타입을 직접 주는 경우
    indent: int = 0                  # 들여쓰기 수준 (Excel alignment.indent, 앞 공백)
    number_format: str = ""          # Excel 표시 형식 (G-10: 표시값 ≠ raw 대조용)
    ref: str = ""                    # 원본 좌표: Excel '시트!D14', 그 외 'R6C9'(표 안 행·열, 1부터)
    display_scale: float = 1.0       # 표시 형식 끝 콤마로 화면에서 축소되는 배율(1000^n)
    origin: str = "disclosed_code"   # 값 출신: disclosed_code | vision_llm


@dataclass
class Block:
    """문서의 의미 단위 하나 (제목·문단·표)."""
    kind: str                         # 'heading' | 'paragraph' | 'table'
    order: int
    section_path: list[str] = field(default_factory=list)
    text: str = ""
    level: int = 0                    # heading 전용
    page: Optional[int] = None        # PDF 쪽 / PPTX 슬라이드 / Excel 시트 순번
    sheet: Optional[str] = None
    grid: Optional[list[list[RawCell]]] = None   # table 전용
    context: list[str] = field(default_factory=list)  # 표 직전 문단(캡션·단위·당기/전기)
    flags: list[str] = field(default_factory=list)    # 'needs_ocr' 등
    origin: str = "disclosed_code"                    # disclosed_code | vision_llm


@dataclass
class Document:
    file_type: str                    # 'html' | 'xml' | 'pdf' | 'xlsx' | 'pptx' | 'txt' | 'json'
    blocks: list[Block] = field(default_factory=list)
    title: str = ""
    page_count: Optional[int] = None
    warnings: list[str] = field(default_factory=list)

    @property
    def tables(self) -> list[Block]:
        return [b for b in self.blocks if b.kind == "table"]
