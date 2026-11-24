"""PDF → 공통 문서 구조(Document). Docling(로컬 모델)으로 읽고, 그림·쪽은 vision.py 규칙으로 라우팅한다.

- Docling OCR은 끈다. 글자는 PDF 텍스트 층에서만 읽는다(이미지 속 글자는 VLM 경로).
- 표: Docling 표 구조 모델. 병합 칸은 칸마다 같은 글자를 복제한다(HTML 로더와 같은 방식).
- 그림: 그림 종류 구분 모델 + 영역 안 텍스트 층 글자 유무로 경로를 정한다.
- 원격 서비스(원격 OCR·VLM API)는 쓰지 않는다(enable_remote_services=False).
"""
from __future__ import annotations

import io
import math
import re
from typing import Optional

from .model import Block, Document, RawCell
from .numbers import clean, parse_number
from .tables import is_total_label
from .vision import (DECOR_CLASSES, MIN_PAGE_CHARS, MIN_PAGE_EDGES, VISION_MAX_CALLS, PageRoute,
                     PictureRoute, VisionHandler, VisionRequest, VisionResult, VisionRunner, VisionTask,
                     route_page, route_picture)

_CONVERTER = None


def _converter():
    """Docling 변환기는 모델 적재가 무거우므로 프로세스마다 한 번만 만든다."""
    global _CONVERTER
    if _CONVERTER is None:
        from docling.datamodel.base_models import InputFormat
        from docling.datamodel.pipeline_options import PdfPipelineOptions
        from docling.document_converter import DocumentConverter, PdfFormatOption

        po = PdfPipelineOptions()
        po.do_ocr = False
        po.do_table_structure = True
        po.do_picture_classification = True
        po.do_picture_description = False
        po.do_chart_extraction = False
        po.generate_picture_images = True     # VLM에 넘길 그림 이미지
        po.images_scale = 2.0
        po.enable_remote_services = False
        _CONVERTER = DocumentConverter(
            format_options={InputFormat.PDF: PdfFormatOption(pipeline_options=po)})
    return _CONVERTER


def _render_page(raw: bytes, pno: int, dpi: int = 150) -> bytes:
    import pymupdf
    with pymupdf.open(stream=raw, filetype="pdf") as pdf:
        return pdf[pno - 1].get_pixmap(dpi=dpi).tobytes("png")


def _edge_ratio(raw: bytes, pno: int, masks: list[tuple[float, float, float, float]],
                dpi: int = 50, threshold: int = 48) -> float:
    """쪽 이미지에서 masks(왼쪽 위 원점, pt)를 흰색으로 가린 뒤, 이웃 픽셀과 밝기 차가 큰 픽셀의 비율.

    글자·표·선은 윤곽이 많고, 배경색과 그러데이션은 윤곽이 거의 없다.
    """
    import pymupdf
    with pymupdf.open(stream=raw, filetype="pdf") as pdf:
        pix = pdf[pno - 1].get_pixmap(dpi=dpi, colorspace=pymupdf.csGRAY)
    k = dpi / 72
    for x0, y0, x1, y1 in masks:
        ir = pymupdf.IRect(int(x0 * k) - 2, int(y0 * k) - 2, int(x1 * k) + 3, int(y1 * k) + 3) & pix.irect
        if not ir.is_empty:
            pix.set_rect(ir, (255,))
    w, h, px = pix.width, pix.height, pix.samples
    if w < 2 or h < 2:
        return 0.0
    n = 0
    for y in range(h - 1):
        row, nxt = px[y * w:(y + 1) * w], px[(y + 1) * w:(y + 2) * w]
        for x in range(w - 1):
            v = row[x]
            if abs(v - row[x + 1]) > threshold or abs(v - nxt[x]) > threshold:
                n += 1
    return n / ((w - 1) * (h - 1))


def _png(img) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def load_pdf(raw: bytes, vision: Optional[VisionHandler] = None,
             vision_timeout: float = 600.0,
             vision_max_calls: Optional[int] = VISION_MAX_CALLS) -> Document:
    """PDF 바이트 → Document. vision이 없으면 VLM이 필요한 곳에 needs_vision 표시만 남긴다.

    vision_max_calls: 문서 하나에서 처리기를 부르는 최대 횟수(비용 상한). 넘는 영역은 needs_vision.
    """
    from docling.datamodel.base_models import DocumentStream
    from docling_core.types.doc import (PictureItem, SectionHeaderItem, TableItem, TextItem,
                                        TitleItem)
    from .loaders import _Builder

    ddoc = _converter().convert(DocumentStream(name="input.pdf", stream=io.BytesIO(raw))).document
    b = _Builder("pdf")
    b.doc.page_count = len(ddoc.pages) or None
    runner = VisionRunner(vision, timeout=vision_timeout, max_calls=vision_max_calls)
    state = {"needs_vision": 0, "page": 0}

    def page_of(item) -> Optional[int]:
        return item.prov[0].page_no if getattr(item, "prov", None) else None

    def bbox_of(item) -> Optional[tuple[float, float, float, float]]:
        if not getattr(item, "prov", None):
            return None
        p = item.prov[0]
        bb = p.bbox.to_top_left_origin(page_height=ddoc.pages[p.page_no].size.height)
        return (round(bb.l, 1), round(bb.t, 1), round(bb.r, 1), round(bb.b, 1))

    def mark(start: int, flags: list[str], bbox) -> None:
        """start 이후 새로 붙은 블록에 표시와 위치를 단다."""
        for blk in b.doc.blocks[start:]:
            blk.flags = list(dict.fromkeys(blk.flags + flags))
            if bbox is not None and blk.bbox is None:
                blk.bbox = bbox

    def placeholder(flags: list[str], bbox) -> None:
        b.doc.blocks.append(Block("paragraph", len(b.doc.blocks), b.path, "", page=b.page,
                                  flags=flags, bbox=bbox))
        if "needs_vision" in flags:
            state["needs_vision"] += 1

    def emit_vision(res: VisionResult, bbox, flags: list[str]) -> None:
        start = len(b.doc.blocks)
        for para in res.paragraphs:
            b.paragraph(para, False, origin="vision_llm")
        if res.description:
            b.paragraph(res.description, False, origin="vision_llm")
        for tb in res.tables:
            b.table([[RawCell(clean(x)) for x in row] for row in tb],
                    flags=["vision"] + vision_table_flags(tb), origin="vision_llm")
        mark(start, ["vision"] + flags, bbox)

    def ask(task: VisionTask, image_png: Optional[bytes], pno: int, bbox, cls: str = ""):
        if not runner.enabled or not image_png:
            return None
        return runner.run(VisionRequest(task, image_png, pno, bbox, cls))

    def class_of(item) -> str:
        meta = getattr(item, "meta", None)
        if meta is not None and getattr(meta, "classification", None) is not None:
            return meta.classification.get_main_prediction().class_name
        return ""

    # 1) 쪽마다 텍스트 층 글자 수와 그림 종류 (그림 안 글자 포함) → 쪽 경로
    chars: dict[int, int] = {}
    pics: dict[int, list[str]] = {}
    masks: dict[int, list] = {}          # 이미 설명된 영역: 글자 있는 텍스트 항목, 로고·아이콘
    unread_table: set[int] = set()       # 칸 글자가 하나도 없는 표 (스캔한 표)
    for item, _ in ddoc.iterate_items(traverse_pictures=True):
        pno = page_of(item)
        if pno is None:
            continue
        if isinstance(item, TextItem):
            n = len(clean(item.text))
            chars[pno] = chars.get(pno, 0) + n
            if n and bbox_of(item):
                masks.setdefault(pno, []).append(bbox_of(item))
        elif isinstance(item, TableItem):
            n = sum(len(clean(c.text)) for c in item.data.table_cells)
            chars[pno] = chars.get(pno, 0) + n
            if not n:
                unread_table.add(pno)
        elif isinstance(item, PictureItem):
            cls = class_of(item)
            pics.setdefault(pno, []).append(cls)
            if cls in DECOR_CLASSES and bbox_of(item):
                masks.setdefault(pno, []).append(bbox_of(item))
    n_pages = b.doc.page_count or 0

    def unread(p: int) -> bool:
        if chars.get(p, 0) >= MIN_PAGE_CHARS:
            return False   # 글자 있는 쪽은 Docling 경로라 볼 필요 없음
        return p in unread_table or _edge_ratio(raw, p, masks.get(p, [])) >= MIN_PAGE_EDGES

    page_route = {p: route_page(chars.get(p, 0), pics.get(p, []), unread(p)) for p in range(1, n_pages + 1)}
    text_pages = {p for p, r in page_route.items() if r == PageRoute.TEXT}

    def image_page(pno: int) -> None:
        """글자 없는 쪽 → 빈 쪽·장식 쪽은 표시만, 내용 그림이 있으면 VLM [쪽 옮기기]."""
        if page_route[pno] == PageRoute.EMPTY:
            placeholder(["empty_page"], None)
            return
        if page_route[pno] == PageRoute.DECOR:
            placeholder(["decor_page"], None)
            return
        res = ask(VisionTask.PAGE, _render_page(raw, pno) if runner.enabled else None, pno, None)
        if res is None:
            placeholder(["needs_vision", "image_page"], None)
            return
        b.heading(f"[Vision] {pno}쪽", 2)
        emit_vision(res, None, ["image_page"])

    def enter_page(pno: int) -> None:
        while state["page"] < pno:
            state["page"] += 1
            b.reset_scope(page=state["page"])
            if state["page"] not in text_pages:
                image_page(state["page"])

    def picture(item) -> None:
        """그림 영역 → 경로별 처리."""
        bbox = bbox_of(item)
        pno = page_of(item)
        cls = class_of(item)
        texts = [clean(ch.text) for ch, _ in ddoc.iterate_items(root=item, traverse_pictures=True)
                 if isinstance(ch, TextItem) and ch is not item and clean(ch.text)]
        route = route_picture(cls, texts)
        tag = [f"picture:{cls or 'unknown'}"]
        start = len(b.doc.blocks)

        if route == PictureRoute.CHART_TEXT:
            b.paragraph(" ".join(texts), False)
            mark(start, ["chart"] + tag, bbox)
            return
        if route == PictureRoute.DECOR:
            for t in texts:
                b.paragraph(t, False)
            mark(start, ["picture_text"] + tag, bbox)
            return

        img = item.get_image(ddoc) if runner.enabled else None
        png = _png(img) if img is not None else None
        if route == PictureRoute.CHART_VISION:
            # 영역 안 글자(단위·범례)는 코드가 읽은 문단으로 남기고, 값은 VLM [차트→표]
            for t in texts:
                b.paragraph(t, False)
            mark(start, ["picture_text"] + tag, bbox)
            res = ask(VisionTask.TO_TABLE, png, pno, bbox, cls)
            if res is None:
                placeholder(["needs_vision", "chart"] + tag, bbox)
            else:
                emit_vision(res, bbox, ["chart"] + tag)
            return
        # DESCRIBE: 영역 안 글자는 코드가 읽은 문단으로 남기고, 설명은 VLM에
        for t in texts:
            b.paragraph(t, False)
        mark(start, ["picture_text"] + tag, bbox)
        res = ask(VisionTask.DESCRIBE, png, pno, bbox, cls)
        if res is None:
            placeholder(["needs_vision", "picture"] + tag, bbox)
        else:
            emit_vision(res, bbox, ["picture"] + tag)

    # 2) Docling 항목을 읽는 순서대로 블록으로 (글자 없는 쪽은 그 자리에서 VLM 경로)
    for item, _ in ddoc.iterate_items(traverse_pictures=False):
        pno = page_of(item)
        if pno is None:
            continue
        enter_page(pno)
        if pno not in text_pages:
            continue
        if isinstance(item, PictureItem):
            picture(item)
            continue
        start = len(b.doc.blocks)
        if isinstance(item, TitleItem):
            b.heading(item.text, 1)
        elif isinstance(item, SectionHeaderItem):
            b.heading(item.text, 1 + max(1, int(getattr(item, "level", 1) or 1)))
        elif isinstance(item, TableItem):
            grid = [[RawCell(clean(c.text), is_header=bool(c.column_header)) for c in row]
                    for row in item.data.grid]
            b.table(grid, flags=table_flags(grid))
        elif isinstance(item, TextItem):
            b.paragraph(item.text)
        mark(start, [], bbox_of(item))
    enter_page(n_pages)   # 마지막 항목 뒤의 빈 쪽·이미지 쪽

    runner.close()
    b.doc.warnings += runner.warnings
    if state["needs_vision"]:
        b.doc.warnings.append(f"VLM이 필요한 영역 {state['needs_vision']}개 — 처리기 미연결 또는 실패 (needs_vision)")
    for flag, what in (("repeated_header", "머리글 행이 표 중간에 다시 나오는 표"),
                       ("total_mismatch", "합계 검산이 맞지 않는 표"),
                       ("growth_mismatch", "증감률이 값과 맞지 않는 VLM 표"),
                       ("vision_ragged", "행마다 칸 수가 다른 VLM 표"),
                       ("vision_bad_number", "숫자로 읽히지 않는 칸이 있는 VLM 표")):
        n = sum(1 for blk in b.doc.blocks if flag in blk.flags)
        if n:
            b.doc.warnings.append(f"{what} {n}개 — 표가 합쳐졌거나 값이 밀렸을 수 있음 ({flag})")
    return b.doc


# ─── 표 구조 검증 ─────────────────────────────────────────────

def table_flags(grid: list[list[RawCell]]) -> list[str]:
    flags = []
    if repeated_header(grid):
        flags.append("repeated_header")
    if total_mismatch(grid):
        flags.append("total_mismatch")
    return flags


def repeated_header(grid: list[list[RawCell]]) -> bool:
    """첫 행(머리글)의 글자 칸이 아래 행에서 같은 자리에 다시 나오면 True (두 표가 하나로 합쳐진 흔적).

    첫 행에서 숫자가 아닌 글자 칸이 3개 이상일 때만 본다. 그중 60% 이상이 같은 자리에서 같으면 반복으로 본다.
    한 행 전체가 같은 글자(병합된 주석 행)인 경우는 제외한다.
    """
    if len(grid) < 3:
        return False
    head = {c: clean(x.text) for c, x in enumerate(grid[0])
            if clean(x.text) and parse_number(clean(x.text))[0] is None}
    if len(set(head.values())) < 3:
        return False
    for row in grid[1:]:
        texts = [clean(x.text) for x in row]
        if len(set(t for t in texts if t)) <= 1:
            continue
        same = sum(1 for c, h in head.items() if c < len(texts) and texts[c] == h)
        if same >= max(3, 0.6 * len(head)):
            return True
    return False

def _decimals(text: str) -> int:
    t = clean(text).replace(",", "")
    return len(t.split(".")[1].rstrip("%)")) if "." in t else 0


def total_mismatch(grid: list[list[RawCell]]) -> bool:
    """합계 행 값이 바로 위 항목들의 합과 맞지 않으면 True.

    각 합계 행(라벨이 합계·소계·계·total)마다, 열별로 직전 합계 행 아래부터의 숫자 칸을 더한다.
    덧셈 항목이 2개 이상인 열만 본다. 반올림 오차는 항목 수 × 마지막 자리 반 단위까지 허용한다.
    계층 표(상위 행 + 하위 행이 함께 있는 표)는 이중 합산으로 맞지 않을 수 있어,
    '전체 합' 또는 '들여쓰기 없는 행만의 합' 중 하나라도 맞으면 통과로 본다.
    """
    if not grid:
        return False
    width = max(len(r) for r in grid)
    labels: list[str] = []
    for row in grid:
        lab = ""
        for c in row:
            t = clean(c.text)
            if t and parse_number(t)[0] is None:
                lab = t
                break
        labels.append(lab)
    seg_start = 0
    for r, lab in enumerate(labels):
        if not lab or not is_total_label(lab):
            continue
        for c in range(width):
            tot_txt = grid[r][c].text if c < len(grid[r]) else ""
            tot, unit = parse_number(tot_txt)
            if tot is None or unit:
                continue
            adds, adds_top, dec_add = [], [], 0
            for rr in range(max(seg_start, 1), r):        # 첫 행(머리글)은 더하지 않는다
                row = grid[rr]
                if is_total_label(labels[rr]) or c >= len(row) or any(x.is_header for x in row):
                    continue
                txt = row[c].text
                v, u = parse_number(txt)
                if v is None or u or not clean(txt):
                    continue
                adds.append(v)
                if not labels[rr].startswith(("-", "ㄴ", "·", "–")):
                    adds_top.append(v)
                dec_add = max(dec_add, _decimals(txt))
            if len(adds) < 2:
                continue
            # 반올림 오차: 합계 칸의 반 단위 + 항목마다 반 단위
            tol = 0.5 * 10 ** (-_decimals(tot_txt)) + len(adds) * 0.5 * 10 ** (-dec_add) + 1e-9
            if not any(math.isclose(sum(xs), tot, abs_tol=tol) for xs in (adds, adds_top) if xs):
                return True
        seg_start = r + 1
    return False


# ─── VLM 표 검증 (VLM이 만든 표에만 쓴다) ─────────────────────

_PERIOD_Q = re.compile(r"^(?:([1-4])\s*Q\s*'?(\d{2}|\d{4})|'?(\d{2}|\d{4})\s*\.?\s*([1-4])\s*Q)$", re.I)
_PERIOD_Y = re.compile(r"^(\d{4})\s*년?$")
_NUMBERISH = re.compile(r"^[\d\s,.%+\-−–△▽()（）]*\d[\d\s,.%+\-−–△▽()（）]*$")


def _period(text: str) -> Optional[tuple[int, int]]:
    """'2Q25' → (2025, 2), '25.2Q' → (2025, 2), '2025' → (2025, 0). 기간이 아니면 None."""
    t = clean(text).replace(" ", "")
    m = _PERIOD_Q.match(t)
    if m:
        q, y = (m.group(1), m.group(2)) if m.group(1) else (m.group(4), m.group(3))
        y = int(y)
        return (y + 2000 if y < 100 else y, int(q))
    m = _PERIOD_Y.match(t)
    return (int(m.group(1)), 0) if m else None


def _signed(text: str) -> tuple[Optional[float], str, int]:
    """parse_number + 앞의 '+'와 뒤의 추정·잠정 꼬리표('(e)', '(P)') 허용. (값, 단위, 소수 자릿수)."""
    t = re.sub(r"\((?:e|p|f)\)$", "", clean(text).replace(" ", ""), flags=re.I)
    if t.startswith("+"):
        t = t[1:]
    v, unit = parse_number(t)
    return v, unit, _decimals(t) if v is not None else 0


def _growth_kind(header: str) -> str:
    h = clean(header).lower()
    if "qoq" in h or "전분기" in h:
        return "QoQ"
    if "yoy" in h or "전년" in h:
        return "YoY"
    return ""


def _series_of(header: str) -> str:
    h = re.sub(r"(?i)qoq|yoy|전분기\s*대비|전년\s*동기\s*대비|전분기|전년|증감률|증감|성장률", "", clean(header))
    return re.sub(r"[\s()\[\]·:/-]+", "", h)


def _period_rows(grid: list[list[str]]) -> Optional[tuple[int, dict[tuple[str, tuple[int, int]], list[int]]]]:
    """기간이 들어 있는 열과 {(묶음, 기간): [행 번호]}.

    기간 열이 첫 열이 아니면 첫 열 글자를 묶음(계열·차트 이름)으로 본다. 같은 묶음 안에서
    같은 기간이 여러 행에 나올 수 있다(값은 그중 칸이 채워진 행에서 찾는다).
    """
    width = max(len(r) for r in grid)
    for c in range(width):
        index: dict[tuple[str, tuple[int, int]], list[int]] = {}
        for r, row in enumerate(grid[1:], start=1):
            p = _period(row[c]) if c < len(row) else None
            if p is None:
                continue
            group = row[0] if c != 0 and row else ""
            index.setdefault((group, p), []).append(r)
        if len({p for _, p in index}) >= 2:
            return c, index
    return None


def _cell(grid: list[list[str]], r: int, c: int) -> str:
    return grid[r][c] if c < len(grid[r]) else ""


def _value(grid: list[list[str]], rows: list[int], c: int, prefer: Optional[int] = None):
    """rows 중 c열이 채워진 행의 값. prefer 행이 채워져 있으면 그 값. 여러 값이 다르면 None."""
    if prefer is not None and _cell(grid, prefer, c):
        v = _signed(_cell(grid, prefer, c))
        return v if v[0] is not None and not v[1] else None
    vals = {_cell(grid, r, c) for r in rows if _cell(grid, r, c)}
    if len(vals) != 1:
        return None
    v = _signed(vals.pop())
    return v if v[0] is not None and not v[1] else None


def growth_mismatch(grid: list[list[str]]) -> bool:
    """증감률(QoQ, YoY) 칸이 값 칸으로 다시 계산한 증감률과 맞지 않으면 True (숫자와 칸의 짝 오류).

    - 기간은 행(첫 행이 머리글) 또는 열(머리글 행)에 있다. 열에 있으면 뒤집어서 본다.
    - 증감률 열의 계열: 머리글에서 'YoY' 등을 뺀 이름과 같은 값 열. 이름이 없으면 값 열이 하나일 때 그 열,
      여럿이면 바로 왼쪽의 값 열.
    - 표시된 값은 반올림된 값이다. 값마다 마지막 자리 반 단위, 증감률도 반 단위를 허용하고
      그 범위 안에서 맞출 수 있으면 통과로 본다. 비교할 기준 기간 값이 없으면 보지 않는다.
    """
    grid = [[clean(x) for x in row] for row in grid if row]
    if len(grid) < 3:
        return False
    found = _period_rows(grid)
    if found is None and sum(1 for x in grid[0] if _period(x)) >= 2:
        width = max(len(r) for r in grid)
        grid = [[row[c] if c < len(row) else "" for row in grid] for c in range(width)]
        found = _period_rows(grid)
    if found is None:
        return False
    pcol, index = found
    head = grid[0]
    width = max(len(r) for r in grid)
    kinds = {c: _growth_kind(head[c]) for c in range(min(width, len(head)))}
    skip = {pcol} | ({0} if pcol != 0 else set())
    values = [c for c in range(width) if c not in skip and not kinds.get(c)]
    for gc, kind in kinds.items():
        if not kind or not values:
            continue
        name = _series_of(head[gc])
        vc = next((c for c in values if name and _series_of(head[c]) == name), None)
        if vc is None and name:
            vc = next((c for c in values if c < len(head) and name in _series_of(head[c])), None)
        if vc is None and not name:
            left = [c for c in values if c < gc]
            vc = values[0] if len(values) == 1 else (left[-1] if left else None)
        if vc is None:
            continue
        for (group, (y, q)), rows in index.items():
            if kind == "QoQ":
                if q == 0:
                    continue
                base = (y, q - 1) if q > 1 else (y - 1, 4)
            else:
                base = (y - 1, q)
            if (group, base) not in index:
                continue
            for r in rows:
                g, gu, gd = _signed(_cell(grid, r, gc))
                if g is None or gu != "%":
                    continue
                cur = _value(grid, rows, vc, prefer=r)
                old = _value(grid, index[(group, base)], vc)
                if cur is None or old is None:
                    continue
                (v1, _, d1), (v0, _, d0) = cur, old
                h1, h0, hg = 0.5 * 10 ** -d1, 0.5 * 10 ** -d0, 0.5 * 10 ** -gd
                if v0 - h0 <= 0:
                    continue
                lo = ((v1 - h1) / (v0 + h0) - 1) * 100
                hi = ((v1 + h1) / (v0 - h0) - 1) * 100
                if g + hg < lo - 1e-9 or g - hg > hi + 1e-9:
                    return True
    return False


def vision_table_flags(grid: list[list[str]]) -> list[str]:
    """VLM이 만든 표의 구조 점검. 문제가 있으면 표시를 돌려준다(표는 버리지 않는다)."""
    rows = [row for row in grid if row]
    if not rows:
        return []
    flags = []
    if len({len(r) for r in rows}) > 1:
        flags.append("vision_ragged")      # 행마다 칸 수가 다름
    for row in rows[1:]:
        for x in row[1:]:
            # 숫자·기호로만 된 칸(각주 표시 * 제외)이 숫자로 읽히지 않으면 표시. 단위·설명 글자가 있는 칸은 보지 않는다
            t = re.sub(r"\*+$", "", clean(x))
            if t and _NUMBERISH.match(t) and _period(t) is None and _signed(t)[0] is None:
                flags.append("vision_bad_number")
                break
        else:
            continue
        break
    if growth_mismatch(rows):
        flags.append("growth_mismatch")
    return flags
