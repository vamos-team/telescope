"""원본 파일 → 공통 문서 구조(Document). 형식별 로더 + 자동 판별.

지원: DART HTML(.html/.htm), DART 원문 XML(.xml), PDF, Excel(.xlsx/.xlsm),
CSV, PowerPoint(.pptx), 텍스트(.txt/.md), JSON(DART API 응답 등 레코드 목록).
회사별 분기는 없다. 형식 판별은 확장자 → 내용 스니핑 순.
"""
from __future__ import annotations

import csv
import io
import json
import re
from pathlib import Path
from statistics import median
from typing import Callable, Optional

from .model import Block, Document, RawCell
from .numbers import clean, display_scale, is_numeric

# ─── 제목 판별 (형식 공통) ───────────────────────────────────

_HEADING_PATTERNS: list[tuple[re.Pattern, int]] = [
    (re.compile(r"^(?:제\s*\d+\s*장|[IVX]{1,5}\s*[.)]\s*\S)"), 1),
    (re.compile(r"^\d{1,2}\.\d{1,2}(\.\d{1,2})?\s*\S"), 3),     # 2.9 / 2.2.1
    (re.compile(r"^\d{1,2}\s*\.\s*\S"), 2),                         # 12. 유형자산
    (re.compile(r"^\d{1,2}-\d{1,2}\s*[.)]?\s*\S"), 3),
    (re.compile(r"^[가-하]\s*[.)]\s*\S"), 4),
    (re.compile(r"^[(（]\s*\d{1,2}\s*[)）]\s*\S"), 5),             # (1)
    (re.compile(r"^\d{1,2}\s*\)\s*\S"), 6),                         # 1) 당기
    (re.compile(r"^[①-⑳]\s*\S"), 6),
]
_CLASS_LEVEL = {"section-1": 1, "section-2": 2, "table-group-xbrl": 3, "table-group": 3}
_REGEX_BASE = 10   # 정규식 제목은 클래스 제목 아래로 들어가도록 오프셋


def _regex_heading_level(text: str) -> Optional[int]:
    t = clean(text)
    if not t or len(t) > 70 or t.endswith(("다.", "다", "음.", "함.")):
        return None
    for pat, lv in _HEADING_PATTERNS:
        if pat.match(t):
            return _REGEX_BASE + lv
    return None


class _Builder:
    """블록 순서·섹션 경로·표 문맥을 관리하는 공용 빌더."""

    def __init__(self, file_type: str):
        self.doc = Document(file_type=file_type)
        self._stack: list[tuple[int, str]] = []
        self._since_table: list[str] = []
        self.page: Optional[int] = None
        self.sheet: Optional[str] = None

    @property
    def path(self) -> list[str]:
        return [t for _, t in self._stack]

    def heading(self, text: str, level: int) -> None:
        text = clean(text)
        if not text:
            return
        while self._stack and self._stack[-1][0] >= level:
            self._stack.pop()
        self._stack.append((level, text))
        self.doc.blocks.append(Block("heading", len(self.doc.blocks), self.path[:-1], text,
                                     level=level, page=self.page, sheet=self.sheet))
        # 큰 제목은 문맥을 새로 시작, '① 당기'·'1) 당기' 같은 작은 표지는 앞 문단('사용권자산의
        # 변동내역은…')을 지우지 않고 덧붙인다
        if level < _REGEX_BASE + 5 and level not in (4, 5, 6):
            self._since_table = [text]
        else:
            self._since_table.append(text)
        if not self.doc.title:
            self.doc.title = text

    def paragraph(self, text: str, detect_heading: bool = True,
                  origin: str = "disclosed_code") -> None:
        text = clean(text)
        if not text:
            return
        if detect_heading:
            lv = _regex_heading_level(text)
            if lv is not None:
                self.heading(text, lv)
                return
        self.doc.blocks.append(Block("paragraph", len(self.doc.blocks), self.path, text,
                                     page=self.page, sheet=self.sheet, origin=origin))
        self._since_table.append(text)

    def table(self, grid: list[list[RawCell]], flags: list[str] | None = None,
              origin: str = "disclosed_code") -> None:
        if not grid or not any(clean(c.text) or c.number is not None for r in grid for c in r):
            return
        ctx = list(reversed(self._since_table[-6:]))     # 가까운 문단 우선
        for i, row in enumerate(grid):                   # 원본 좌표가 없는 형식은 표 안 행·열(1부터)
            for j, c in enumerate(row):
                if not c.ref:
                    c.ref = f"R{i + 1}C{j + 1}"
                if origin != "disclosed_code":
                    c.origin = origin
        self.doc.blocks.append(Block("table", len(self.doc.blocks), self.path,
                                     page=self.page, sheet=self.sheet, grid=grid,
                                     context=ctx, flags=flags or [], origin=origin))
        # 직전 문맥은 연속된 표(당기/전기 쌍)에서도 단위가 이어지도록 단위 문장만 남긴다
        self._since_table = [t for t in self._since_table if "단위" in t][-1:]

    def reset_scope(self, page: Optional[int] = None, sheet: Optional[str] = None) -> None:
        self.page, self.sheet = page, sheet


def _is_caption_grid(grid: list[list[RawCell]]) -> bool:
    """레이아웃용 표(제목·단위·당기 표시만 있는 작은 무테 표) 판별.

    숫자 칸이 하나도 없고, (4줄 이하) 또는 (내용이 한두 열뿐인 10줄 이하)인 표.
    DART 본문 재무제표 앞의 제목 표는 '제목 / 제 N 기 …(3줄) / (단위 : 백만원)' 5줄이라
    줄 수만으로 자르면 단위 문구를 잃는다.
    """
    for row in grid:
        for c in row:
            if c.number is not None or is_numeric(c.text):
                return False
    if len(grid) <= 4:
        return True
    width = max((len({clean(c.text) for c in row if clean(c.text)}) for row in grid), default=0)
    return len(grid) <= 10 and width <= 2


def _grid_from_spans(rows: list[list[tuple[str, int, int, bool]]]) -> list[list[RawCell]]:
    """[(text, rowspan, colspan, is_header)] 행 목록 → 병합 해제 격자."""
    occ: dict[tuple[int, int], RawCell] = {}
    for r, cells in enumerate(rows):
        c = 0
        for text, rs, cs, hdr in cells:
            while (r, c) in occ:
                c += 1
            for dr in range(max(1, rs)):
                for dc in range(max(1, cs)):
                    occ.setdefault((r + dr, c + dc), RawCell(text, is_header=hdr))   # 칸마다 별도 객체
            c += max(1, cs)
    if not occ:
        return []
    nr = max(r for r, _ in occ) + 1
    nc = max(c for _, c in occ) + 1
    return [[occ.get((r, c), RawCell("")) for c in range(nc)] for r in range(nr)]


# ─── HTML / DART XML ────────────────────────────────────────

def _decode(raw: bytes) -> str:
    head = raw[:2000].decode("ascii", "ignore").lower()
    m = re.search(r'(?:charset|encoding)=["\']?([\w-]+)', head)
    encs = ([m.group(1)] if m else []) + ["utf-8", "cp949", "euc-kr"]
    for enc in encs:
        try:
            return raw.decode(enc)
        except (LookupError, UnicodeDecodeError):
            continue
    return raw.decode("utf-8", "replace")


def load_html(raw: bytes, file_type: str = "html") -> Document:
    from lxml import html as lhtml

    text = _decode(raw)
    text = re.sub(r"^<\?xml[^>]*\?>", "", text.lstrip())
    # <BR>은 줄바꿈으로 보존 — 한 <P> 안에 '12. 유형자산<BR/>(1) …'처럼 제목·본문이 섞인 구형 공시 대비
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    root = lhtml.fromstring(text) if text.strip() else None
    b = _Builder(file_type)
    if root is None:
        return b.doc
    cell_tags = {"td", "th", "te", "tu"}

    def table_grid(tbl) -> list[list[RawCell]]:
        rows = []
        for tr in tbl.iter("tr"):
            # 중첩 표의 tr 제외
            if next((a for a in tr.iterancestors("table")), None) is not tbl:
                continue
            in_head = any(a.tag == "thead" for a in tr.iterancestors())
            cells = []
            for td in tr:
                if not isinstance(td.tag, str) or td.tag.lower() not in cell_tags:
                    continue
                def _span(name):
                    try:
                        return int(re.sub(r"\D", "", td.get(name, "1") or "1") or 1)
                    except ValueError:
                        return 1
                cells.append((clean(td.text_content()), _span("rowspan"), _span("colspan"),
                              in_head or td.tag.lower() == "th"))
            if cells:
                rows.append(cells)
        return _grid_from_spans(rows)

    def walk(el) -> None:
        for ch in el:
            if not isinstance(ch.tag, str):
                continue
            tag = ch.tag.lower()
            if tag in ("script", "style", "head"):
                continue
            if tag == "table":
                grid = table_grid(ch)
                if _is_caption_grid(grid):
                    for row in grid:
                        seen = []
                        for c in row:
                            t = clean(c.text)
                            if t and t not in seen:
                                seen.append(t)
                        if seen:
                            b.paragraph(" ".join(seen))
                else:
                    b.table(grid)
                continue
            cls = (ch.get("class") or "").lower()
            if tag == "title":
                lv = next((int(a.tag.split("-")[1]) for a in ch.iterancestors()
                           if isinstance(a.tag, str) and re.match(r"section-\d", a.tag.lower())), 1)
                b.heading(ch.text_content(), lv)
                continue
            if re.match(r"h[1-6]$", tag):
                b.heading(ch.text_content(), int(tag[1]))
                continue
            if tag in ("p", "span", "div", "li", "pre") and not any(
                    isinstance(d.tag, str) and d.tag.lower() in ("table", "p", "div", "title")
                    for d in ch.iterdescendants()):
                if cls in _CLASS_LEVEL:
                    b.heading(ch.text_content(), _CLASS_LEVEL[cls])
                else:
                    for line in re.split(r"\n+", ch.text_content()):
                        b.paragraph(line)
                continue
            walk(ch)

    body = root.find(".//body")
    walk(body if body is not None else root)
    return b.doc


# ─── PDF ────────────────────────────────────────────────────

def load_pdf(raw: bytes, vision: Optional[Callable[[bytes, int], dict]] = None) -> Document:
    """쪽마다 경로를 정한다: 글자가 있으면 코드로(표는 표로), 글자 없는 이미지 쪽만 Vision.

    vision(png_bytes, page_no) → {"paragraphs": [...], "tables": [[[칸,…],…],…]}.
    Vision 결과는 origin='vision_llm'으로 표시되어 코드가 읽은 값과 섞이지 않는다.
    """
    import pdfplumber

    b = _Builder("pdf")
    with pdfplumber.open(io.BytesIO(raw)) as pdf:
        b.doc.page_count = len(pdf.pages)
        sizes = []
        for p in pdf.pages[:20]:
            sizes += [round(ch["size"], 1) for ch in p.chars[:3000]]
        body_size = median(sizes) if sizes else 10.0

        for pno, page in enumerate(pdf.pages, start=1):
            b.reset_scope(page=pno)
            if len(page.chars) < 15:
                flags = ["needs_ocr"] if page.images else ["empty_page"]
                if page.images and vision is not None:
                    try:
                        import pymupdf
                        png = pymupdf.open(stream=raw, filetype="pdf")[pno - 1].get_pixmap(dpi=150).tobytes("png")
                        res = vision(png, pno) or {}
                        b.heading(f"[Vision] {pno}쪽", 2)
                        for para in res.get("paragraphs") or []:
                            b.paragraph(str(para), False, origin="vision_llm")
                        for tb in res.get("tables") or []:
                            b.table([[RawCell(clean(x)) for x in row] for row in tb],
                                    flags=["vision"], origin="vision_llm")
                        flags = ["vision_done"]
                    except Exception as e:
                        b.doc.warnings.append(f"{pno}쪽 Vision 실패: {type(e).__name__}: {e}")
                b.doc.blocks.append(Block("paragraph", len(b.doc.blocks), b.path, "",
                                          page=pno, flags=flags))
                if "needs_ocr" in flags:
                    b.doc.warnings.append(f"{pno}쪽: 글자 없는 이미지 쪽 — 내용 미적재 (Vision 필요)")
                continue
            tables = page.find_tables()
            if not tables:
                lines_txt = (page.extract_text() or "").splitlines()
                numeric_lines = sum(1 for ln in lines_txt if len(re.findall(r"\d[\d,.]*", ln)) >= 2)
                if numeric_lines >= 6:
                    tables = page.find_tables({"vertical_strategy": "text",
                                               "horizontal_strategy": "text"})
            bboxes = [t.bbox for t in tables]

            def outside(obj) -> bool:
                cx, cy = (obj["x0"] + obj["x1"]) / 2, (obj["top"] + obj["bottom"]) / 2
                return not any(x0 <= cx <= x1 and t0 <= cy <= t1 for x0, t0, x1, t1 in bboxes)

            words = [w for w in page.extract_words(extra_attrs=["size", "fontname"],
                                                   keep_blank_chars=False, use_text_flow=True)
                     if outside(w)]
            # 줄 단위로 묶기
            lines: list[dict] = []
            for w in sorted(words, key=lambda w: (round(w["top"]), w["x0"])):
                if lines and abs(lines[-1]["top"] - w["top"]) <= 2.5:
                    lines[-1]["words"].append(w)
                else:
                    lines.append({"top": w["top"], "words": [w]})
            items: list[tuple[float, str, object]] = []
            for ln in lines:
                txt = " ".join(w["text"] for w in ln["words"])
                size = max(w["size"] for w in ln["words"])
                bold = any("bold" in (w.get("fontname") or "").lower() for w in ln["words"])
                items.append((ln["top"], "line", (txt, size, bold)))
            for t in tables:
                items.append((t.bbox[1], "table", t))
            for _, kind, obj in sorted(items, key=lambda x: x[0]):
                if kind == "line":
                    txt, size, bold = obj
                    if size >= body_size * 1.35 and len(txt) <= 80:
                        b.heading(txt, 1 if size >= body_size * 1.8 else 2)
                    elif (size >= body_size * 1.12 or bold) and len(txt) <= 60 and not is_numeric(txt):
                        b.heading(txt, 3)
                    else:
                        b.paragraph(txt)
                else:
                    data = obj.extract() or []
                    grid = []
                    for ri, row in enumerate(data):
                        out, last = [], ""
                        for cell in row:
                            if cell is None and ri == 0:
                                cell = last        # 병합 머리글: 왼쪽 값 이어받기
                            last = cell or ""
                            out.append(RawCell(clean(cell or "")))
                        grid.append(out)
                    b.table(grid)
    return b.doc


# ─── Excel / CSV ────────────────────────────────────────────

def _emit_sheet(b: _Builder, rows: list[list[RawCell]]) -> None:
    """빈 행으로 나뉜 블록마다: 숫자 없는 짧은 블록·1칸 행은 문단, 나머지는 표."""
    blocks: list[list[list[RawCell]]] = []
    cur: list[list[RawCell]] = []
    for row in rows:
        if any(clean(c.text) or c.number is not None for c in row):
            cur.append(row)
        elif cur:
            blocks.append(cur)
            cur = []
    if cur:
        blocks.append(cur)
    for blk in blocks:
        # 앞쪽 1칸짜리 텍스트 행(제목·단위)은 문단으로 떼어 낸다
        while blk:
            filled = [c for c in blk[0] if clean(c.text) or c.number is not None]
            if len(filled) == 1 and filled[0].number is None and not is_numeric(filled[0].text) and len(blk) > 1:
                b.paragraph(filled[0].text)
                blk = blk[1:]
            else:
                break
        if not blk:
            continue
        if _is_caption_grid(blk):
            for row in blk:
                b.paragraph(" ".join(clean(c.text) for c in row if clean(c.text)))
            continue
        # 표 중간의 빈 줄: 이 블록 첫 행에 숫자가 있으면(=자기 머리글이 없음) 바로 앞 표의 연속이다.
        # 따로 떼면 아래쪽 행들이 머리글(분기·연도)을 잃어 기간이 비게 된다.
        first_has_number = any(c.number is not None or is_numeric(c.text) for c in blk[0])
        prev = b.doc.blocks[-1] if b.doc.blocks else None
        if (first_has_number and prev is not None and prev.kind == "table"
                and prev.sheet == b.sheet and "chart" not in prev.flags):
            prev.grid.extend(blk)
            if "merged_after_blank_row" not in prev.flags:
                prev.flags.append("merged_after_blank_row")
        else:
            b.table(blk)


def load_xlsx(raw: bytes) -> Document:
    import openpyxl

    wb = openpyxl.load_workbook(io.BytesIO(raw), data_only=True)   # 수식은 캐시된 값
    b = _Builder("xlsx")
    for si, ws in enumerate(wb.worksheets, start=1):
        b.reset_scope(page=si, sheet=ws.title)
        b.heading(ws.title, 1)
        if ws.sheet_state != "visible":
            b.doc.warnings.append(f"숨김 시트 포함: {ws.title}")
        merged: dict[tuple[int, int], tuple[int, int]] = {}
        for rng in ws.merged_cells.ranges:
            for r in range(rng.min_row, rng.max_row + 1):
                for c in range(rng.min_col, rng.max_col + 1):
                    merged[(r, c)] = (rng.min_row, rng.min_col)
        rows: list[list[RawCell]] = []
        n_scaled = 0
        for row in ws.iter_rows(values_only=False):         # 숨김 행·열 포함 (G-5)
            out = []
            for cell in row:
                src = cell
                if (cell.row, cell.column) in merged:
                    r0, c0 = merged[(cell.row, cell.column)]
                    src = ws.cell(r0, c0)
                v = src.value
                num = float(v) if isinstance(v, (int, float)) and not isinstance(v, bool) else None
                txt = "" if v is None else (str(v) if num is None else repr(num))
                ind = 0
                try:
                    ind = int(src.alignment.indent or 0)
                except Exception:
                    pass
                if isinstance(v, str):
                    ind = max(ind, (len(v) - len(v.lstrip(" 　"))) // 2)
                fmt = src.number_format or ""
                scale = display_scale(fmt) if num is not None else 1.0
                out.append(RawCell(txt.strip() if num is None else txt, number=num, indent=ind,
                                   is_header=bool(getattr(src.font, "b", False)) and num is None,
                                   number_format=fmt if num is not None else "",
                                   ref=f"{ws.title}!{cell.coordinate}", display_scale=scale))
                if scale != 1.0:
                    n_scaled += 1
                    if n_scaled <= 5:
                        b.doc.warnings.append(
                            f"{ws.title}!{cell.coordinate}: 표시 형식 '{fmt}'이 화면에서 1/{scale:,.0f}로 축소 표시 — raw 값 사용 (G-10)")
            rows.append(out)
        if n_scaled > 5:
            b.doc.warnings.append(f"{ws.title}: 축소 표시 형식 칸 총 {n_scaled}개 (셀별 배율은 ingest_cells.display_scale)")
        _emit_sheet(b, rows)
    return b.doc


def load_csv(raw: bytes) -> Document:
    text = _decode(raw)
    b = _Builder("csv")
    rows = [[RawCell(clean(x), ref=f"R{i + 1}C{j + 1}") for j, x in enumerate(r)]
            for i, r in enumerate(csv.reader(io.StringIO(text)))]
    _emit_sheet(b, rows)
    return b.doc


# ─── PowerPoint ─────────────────────────────────────────────

def load_pptx(raw: bytes) -> Document:
    from pptx import Presentation

    prs = Presentation(io.BytesIO(raw))
    b = _Builder("pptx")
    b.doc.page_count = len(prs.slides)

    def shapes_in(shapes):
        for sh in shapes:
            if sh.shape_type == 6 and hasattr(sh, "shapes"):     # GROUP
                yield from shapes_in(sh.shapes)
            else:
                yield sh

    for sno, slide in enumerate(prs.slides, start=1):
        b.reset_scope(page=sno)
        title_sh = slide.shapes.title
        b.heading(title_sh.text if title_sh is not None and title_sh.text.strip() else f"슬라이드 {sno}", 1)
        for sh in sorted(shapes_in(slide.shapes), key=lambda s: (s.top or 0, s.left or 0)):
            if title_sh is not None and sh.shape_id == title_sh.shape_id:
                continue
            if getattr(sh, "has_table", False) and sh.has_table:
                grid = []
                for row in sh.table.rows:
                    grid.append([RawCell(clean(cell.text)) for cell in row.cells])
                b.table(grid)
            elif getattr(sh, "has_chart", False) and sh.has_chart:
                ch = sh.chart
                try:
                    cats = list(ch.plots[0].categories)
                    grid = [[RawCell("", is_header=True)] + [RawCell(str(c), is_header=True) for c in cats]]
                    for s in ch.plots[0].series:
                        grid.append([RawCell(s.name or "")] +
                                    [RawCell("" if v is None else repr(float(v)),
                                             number=None if v is None else float(v)) for v in s.values])
                    b.paragraph(f"[차트] {ch.chart_title.text_frame.text if ch.has_title else ''}", False)
                    b.table(grid, flags=["chart"])
                except Exception as e:  # 차트 형식 다양 — 실패는 경고로
                    b.doc.warnings.append(f"slide {sno} chart: {e}")
            elif getattr(sh, "has_text_frame", False) and sh.has_text_frame:
                for para in sh.text_frame.paragraphs:
                    t = "".join(r.text for r in para.runs)
                    b.paragraph(t)
        if slide.has_notes_slide:
            notes = slide.notes_slide.notes_text_frame.text if slide.notes_slide.notes_text_frame else ""
            if clean(notes):
                b.paragraph(f"[발표자 노트] {notes}", False)
    return b.doc


# ─── 텍스트 / JSON ──────────────────────────────────────────

def load_text(raw: bytes) -> Document:
    b = _Builder("txt")
    text = _decode(raw)
    for para in re.split(r"\n\s*\n", text):
        p = para.strip()
        m = re.match(r"^(#{1,6})\s+(.*)", p)
        if m:
            b.heading(m.group(2), len(m.group(1)))
        elif "|" in p and p.count("\n") >= 2 and re.search(r"^\s*\|?\s*-{3,}", p, re.M):
            rows = [ln.strip().strip("|").split("|") for ln in p.splitlines()
                    if ln.strip() and not re.match(r"^\s*\|?\s*[-:| ]+$", ln)]
            b.table([[RawCell(clean(x), is_header=(i == 0)) for x in r] for i, r in enumerate(rows)])
        else:
            b.paragraph(" ".join(p.splitlines()))
    return b.doc


def load_json(raw: bytes) -> Document:
    b = _Builder("json")
    data = json.loads(_decode(raw))
    records = None
    if isinstance(data, list):
        records = data
    elif isinstance(data, dict):
        for k in ("list", "data", "items", "rows", "result"):
            if isinstance(data.get(k), list):
                records = data[k]
                for kk, vv in data.items():
                    if kk != k and isinstance(vv, (str, int, float)):
                        b.paragraph(f"{kk}: {vv}", False)
                break
    if records and all(isinstance(r, dict) for r in records):
        keys: list[str] = []
        for r in records:
            for k in r:
                if k not in keys:
                    keys.append(k)
        grid = [[RawCell(k, is_header=True) for k in keys]]
        for r in records:
            grid.append([RawCell(clean(r.get(k, ""))) for k in keys])
        b.table(grid)
    else:
        b.paragraph(json.dumps(data, ensure_ascii=False)[:20000], False)
    return b.doc


# ─── 판별 ───────────────────────────────────────────────────

LOADERS: dict[str, Callable[[bytes], Document]] = {
    "html": load_html, "xml": lambda r: load_html(r, "xml"), "pdf": load_pdf,
    "xlsx": load_xlsx, "csv": load_csv, "pptx": load_pptx, "txt": load_text, "json": load_json,
}
_EXT = {".html": "html", ".htm": "html", ".xml": "xml", ".pdf": "pdf", ".xlsx": "xlsx",
        ".xlsm": "xlsx", ".csv": "csv", ".pptx": "pptx", ".txt": "txt", ".md": "txt",
        ".json": "json"}


def sniff(path: Path, raw: bytes) -> Optional[str]:
    ft = _EXT.get(path.suffix.lower())
    if ft:
        return ft
    head = raw[:512]
    if head.startswith(b"%PDF"):
        return "pdf"
    if head.startswith(b"PK"):
        import zipfile
        try:
            names = zipfile.ZipFile(io.BytesIO(raw)).namelist()
        except zipfile.BadZipFile:
            return None
        if any(n.startswith("xl/") for n in names):
            return "xlsx"
        if any(n.startswith("ppt/") for n in names):
            return "pptx"
        return None
    low = head.lower()
    if b"<html" in low or b"<!doctype html" in low or b"<table" in low:
        return "html"
    if low.lstrip().startswith(b"<?xml") or b"<document" in low:
        return "xml"
    if low.lstrip()[:1] in (b"{", b"["):
        return "json"
    return "txt"


def load(path: Path, raw: Optional[bytes] = None,
         vision: Optional[Callable[[bytes, int], dict]] = None) -> Document:
    raw = raw if raw is not None else path.read_bytes()
    ft = sniff(path, raw)
    if ft is None or ft not in LOADERS:
        raise ValueError(f"지원하지 않는 형식: {path.name}")
    if ft == "pdf":
        return load_pdf(raw, vision=vision)
    return LOADERS[ft](raw)
