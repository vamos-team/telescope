"""파서 결과(parse()의 dict) → LLM이 읽기 좋은 마크다운. 네트워크·DB에 접근하지 않는 순수 변환.

    from parsers import parse, to_markdown, split_markdown
    res = parse("report.pdf")
    md = to_markdown(res)                       # 문서 전체
    chunks = split_markdown(res, max_chars=4000)   # 장·절 단위 조각 (검색·LLM 입력용)

- 쪽이 바뀌면 한 줄 `[p.13]`, Excel은 `[시트: 손익계산서]`을 적는다. LLM이 근거 위치를 인용할 수 있게 한다.
- 표 위에 단위·기간(코드가 읽은 값)을 적는다. 단위 변환은 하지 않는다(원문 단위 그대로).
- VLM이 읽은 내용(origin="vision_llm")과 구조 오류가 의심되는 표는 표시를 붙인다.
- 마크다운은 맥락을 읽는 용도다. 숫자 계산은 parse()의 tables 칸 데이터나 재무 DB로 한다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from .numbers import clean, parse_number

DEFAULT_MAX_CHARS = 4000
WIDE_HEADER_CHARS = 1000   # 머리글 줄이 이보다 길면 공통 머리글을 표 위 한 줄로 뺀다
VISION_MARK = "[VLM 판독, 확인 필요]"

# 표 구조 오류 표시 → 표 위 경고 문구
TABLE_WARNINGS = {
    "repeated_header": "머리글이 표 중간에 다시 나옴 (두 표가 합쳐졌을 수 있음)",
    "total_mismatch": "합계 행이 항목 합과 맞지 않음 (값이 밀렸을 수 있음)",
    "growth_mismatch": "증감률이 값과 맞지 않음 (숫자가 다른 칸에 들어갔을 수 있음)",
    "vision_ragged": "행마다 칸 수가 다름",
    "vision_bad_number": "숫자로 읽히지 않는 칸이 있음",
}
_SKIP_FLAGS = {"empty_page", "decor_page"}


# ─── 표 ─────────────────────────────────────────────────────

def _cell(text: str) -> str:
    """마크다운 표 칸: 줄바꿈은 공백, '|'는 이스케이프."""
    return clean(text).replace("\\", "\\\\").replace("|", "\\|")


def _is_number(text: str) -> bool:
    t = clean(text)
    return bool(t) and parse_number(t.lstrip("+"))[0] is not None


def _banner(row: list[str], width: int) -> bool:
    """행 전체에 걸친 제목·주석·단위 행인가.

    숫자 없이 한 글자만 행 전체에 복제됐거나, 두 글자 중 하나가 단위 표기('단위 : 억원')인 행.
    머리글의 열 묶음(예: '구분', '당기', '당기')은 단위 표기가 없어서 머리글로 남는다.
    """
    texts = [clean(x) for x in row if clean(x)]
    if width < 2 or not texts or any(_is_number(t) for t in texts):
        return False
    distinct = list(dict.fromkeys(texts))
    return len(distinct) == 1 or (len(distinct) == 2 and any("단위" in t for t in distinct))


def _dedupe_labels(row: list[str]) -> list[str]:
    """병합 칸 복제로 옆 칸과 같은 글자(숫자 아님)는 비운다. 숫자 값은 같아도 그대로 둔다."""
    out = []
    for i, x in enumerate(row):
        t = clean(x)
        if i and t and t == clean(row[i - 1]) and not _is_number(t):
            out.append("")
        else:
            out.append(t)
    return out


@dataclass
class TableMd:
    notes: list[str]              # 표 위 줄 (단위·기간, 경고, 제목 행)
    cols: list[str]               # 열 머리글 (병합 복제 정리 전)
    body: list[list[str]]         # 본문 칸
    label_cols: int = 1           # 왼쪽 행 이름 열 수 (열을 나눌 때 조각마다 반복)

    def header(self, cols: Optional[list[int]] = None) -> str:
        idx = list(range(len(self.cols))) if cols is None else cols
        if not idx:
            return ""
        names = _dedupe_labels([self.cols[c] for c in idx])
        return "| " + " | ".join(_cell(x) for x in names) + " |\n|" + "---|" * len(idx)

    def row(self, r: int, cols: Optional[list[int]] = None) -> str:
        idx = list(range(len(self.cols))) if cols is None else cols
        return "| " + " | ".join(_cell(self.body[r][c]) for c in idx) + " |"

    def render(self, rows: Optional[list[int]] = None, cols: Optional[list[int]] = None, part: str = "") -> str:
        notes = self.notes + ([part] if part else [])
        rows = range(len(self.body)) if rows is None else rows
        table = "\n".join([self.header(cols)] + [self.row(r, cols) for r in rows])
        return "\n".join(notes) + "\n\n" + table if notes else table

    def column_groups(self, limit: int) -> list[list[int]]:
        """머리글 줄이 limit 이하가 되도록 값 열을 나눈다. 행 이름 열은 묶음마다 반복한다."""
        n = len(self.cols)
        labels = list(range(min(self.label_cols, n)))
        values = list(range(len(labels), n))
        if len(self.header()) <= limit or not values:
            return [list(range(n))]
        groups, g = [], []
        for c in values:
            if g and len(self.header(labels + g + [c])) > limit:
                groups.append(labels + g)
                g = []
            g.append(c)
        groups.append(labels + g)
        return groups


_INT_FLOAT = re.compile(r"^-?\d+\.0$")


def table_markdown(table: dict, flags: list[str], origin: str, skip_caption: str = "",
                   file_type: str = "") -> TableMd:
    grid = [[clean(x) for x in r] for r in table.get("rows") or []]
    h = min(int(table.get("header_rows") or 0), len(grid))
    if file_type in ("xlsx", "csv"):
        # Excel 로더는 숫자 칸을 '2022.0'처럼 넘긴다. 머리글(연도)만 '2022'로 적고, 값은 그대로 둔다
        grid = [[x[:-2] if i < h and _INT_FLOAT.match(x) else x for x in r] for i, r in enumerate(grid)]
    width = max((len(r) for r in grid), default=0)
    grid = [r + [""] * (width - len(r)) for r in grid]
    keep = [c for c in range(width) if any(r[c] for r in grid)]   # 모든 행이 빈 열은 뺀다
    grid = [[r[c] for c in keep] for r in grid]
    label_cols = sum(1 for c in keep if c < int(table.get("label_cols") or 1))
    width = len(keep)

    notes: list[str] = []
    if origin == "vision_llm":
        notes.append(f"> {VISION_MARK} VLM이 이미지에서 옮긴 표")
    for f in flags:
        if f in TABLE_WARNINGS:
            notes.append(f"> ⚠ 구조 확인 필요: {TABLE_WARNINGS[f]}")
    meta = []
    cap = clean(table.get("caption") or "")
    if cap and cap != clean(skip_caption):
        meta.append(f"표: {cap}")
    if table.get("unit"):
        meta.append(f"단위: {table['unit']}")
    if table.get("period_hint"):
        meta.append(f"기간: {table['period_hint']}")
    if meta:
        notes.append(" · ".join(meta))

    head_rows, body = grid[:h], grid[h:]
    kept_head = []
    for r in head_rows:
        if _banner(r, width):
            notes.append(" · ".join(dict.fromkeys(clean(x) for x in r if clean(x))))
        else:
            kept_head.append(r)
    body_rows = []
    for r in body:
        if _banner(r, width):
            # 표 중간의 전체 폭 주석 행은 첫 칸에만 남긴다
            body_rows.append([clean(next(x for x in r if clean(x)))] + [""] * (width - 1))
        else:
            body_rows.append(_dedupe_labels(r))

    heads = [list(dict.fromkeys(clean(r[c]) for r in kept_head if clean(r[c]))) for c in range(width)]
    # 머리글 줄이 아주 긴 표만: 모든 값 열에 공통인 위쪽 머리글은 한 줄로 뺀다 (넓은 다단 머리글 표)
    vals = heads[label_cols:]
    common = []
    if len(" | ".join(" / ".join(hp) for hp in heads)) > WIDE_HEADER_CHARS and len(vals) >= 2 and all(vals):
        for k in range(min(len(v) for v in vals)):
            if len({tuple(v[:k + 1]) for v in vals}) != 1:
                break
            common = vals[0][:k + 1]
        if all(len(v) > len(common) for v in vals) and common:
            notes.append("열 머리글 공통: " + " / ".join(common))
            heads = heads[:label_cols] + [v[len(common):] for v in vals]
    cols = [" / ".join(hp) for hp in heads]
    return TableMd(notes, cols, body_rows, label_cols)


# ─── 블록 → 조각 ─────────────────────────────────────────────

@dataclass
class Piece:
    """마크다운으로 바꾼 블록 하나. 나누기의 최소 단위(표는 행 단위로 더 나눌 수 있다)."""
    text: str
    section_path: list[str] = field(default_factory=list)
    page: Optional[int] = None
    sheet: Optional[str] = None
    table: Optional[TableMd] = None


def _locator(page: Optional[int], sheet: Optional[str], file_type: str) -> Optional[str]:
    if file_type in ("xlsx", "csv") and sheet:
        return f"[시트: {sheet}]"
    if page is not None and file_type in ("pdf", "pptx"):
        return f"[p.{page}]"
    return None


def _pieces(res: dict) -> list[Piece]:
    tables = {t["table_no"]: t for t in res.get("tables", [])}
    out: list[Piece] = []
    blocks = res.get("blocks", [])
    last_para = ""
    i = 0
    while i < len(blocks):
        b = blocks[i]
        flags = b.get("flags") or []
        base = dict(section_path=b.get("section_path") or [], page=b.get("page"), sheet=b.get("sheet"))
        text = clean(b.get("text") or "")
        if any(f in _SKIP_FLAGS for f in flags):
            i += 1
            continue
        if b["kind"] == "heading":
            level = min(max(int(b.get("level") or 1), 1) + 1, 6)
            out.append(Piece("#" * level + " " + text, **base))
            last_para = ""
        elif b["kind"] == "table":
            t = tables.get(b.get("table_no"))
            if t is not None:
                tm = table_markdown(t, flags, b.get("origin", ""), skip_caption=last_para,
                                    file_type=res.get("file_type", ""))
                out.append(Piece(tm.render(), table=tm, **base))
            last_para = ""
        elif "needs_vision" in flags:
            what = "쪽" if "image_page" in flags else ("차트" if "chart" in flags else "그림")
            out.append(Piece(f"> [읽지 못한 {what}: 이미지에만 있는 내용, VLM 처리 안 됨]", **base))
        elif "picture_text" in flags:
            # 같은 그림 안 글자는 한 줄로 묶는다
            bbox = b.get("bbox")
            texts = [text] if text else []
            while (i + 1 < len(blocks) and "picture_text" in (blocks[i + 1].get("flags") or [])
                   and blocks[i + 1].get("bbox") == bbox and blocks[i + 1].get("page") == b.get("page")):
                i += 1
                t2 = clean(blocks[i].get("text") or "")
                if t2:
                    texts.append(t2)
            if texts:
                out.append(Piece("[그림 안 글자] " + " / ".join(texts), **base))
        elif "chart" in flags and b.get("origin") != "vision_llm":
            if text:
                out.append(Piece("[차트 안 글자, 순서가 섞일 수 있음] " + text, **base))
        elif text:
            if b.get("origin") == "vision_llm":
                out.append(Piece(f"> {VISION_MARK} {text}", **base))
            else:
                out.append(Piece(text, **base))
                last_para = text
        i += 1
    return out


def _title(res: dict) -> str:
    name = Path(res.get("file") or "").name
    return clean(res.get("title") or "") or name or "문서"


def to_markdown(res: dict) -> str:
    """parse() 결과 → 마크다운 문서 하나."""
    ft = res.get("file_type", "")
    name = Path(res.get("file") or "").name
    lines = [f"# {_title(res)}", f"원본: {name} ({ft})" if name else f"형식: {ft}"]
    loc = None
    for p in _pieces(res):
        cur = _locator(p.page, p.sheet, ft)
        if cur and cur != loc:
            lines.append(cur)
            loc = cur
        lines.append(p.text)
    warns = res.get("warnings") or []
    if warns:
        lines.append("## 파싱 경고")
        lines.extend(f"- {w}" for w in warns)
    return "\n\n".join(lines) + "\n"


# ─── 나누기 ──────────────────────────────────────────────────

def _split_text(text: str, limit: int) -> list[str]:
    """긴 문단을 문장(마침표·줄바꿈) 경계에서, 안 되면 글자 수로 나눈다."""
    parts, cur = [], ""
    for sent in re.split(r"(?<=[.!?。다])\s+", text):
        while len(sent) > limit:
            if cur:
                parts.append(cur)
                cur = ""
            parts.append(sent[:limit])
            sent = sent[limit:]
        if cur and len(cur) + 1 + len(sent) > limit:
            parts.append(cur)
            cur = sent
        else:
            cur = f"{cur} {sent}" if cur else sent
    if cur:
        parts.append(cur)
    return parts


def split_markdown(res: dict, max_chars: int = DEFAULT_MAX_CHARS) -> list[dict]:
    """parse() 결과 → 장·절 단위 마크다운 조각 목록.

    - 장·절(section_path 앞 두 단계)이 바뀌면 새 조각을 시작한다. 단, 지금 조각이 max_chars의 1/4보다
      짧으면 다음 절과 합친다(너무 작은 조각 방지).
    - 조각 길이는 max_chars 이하다. 블록 경계에서 나누고, 긴 표는 행 단위로 나누며 머리글을 반복한다.
      행 하나가 max_chars보다 길면 그 행만 넘을 수 있다.
    - 조각마다 첫 줄에 문서 이름·장·절 경로·쪽 범위를 적는다.

    반환: [{"index", "text", "chars", "section_paths", "page_start", "page_end", "sheets"}]
    """
    ft = res.get("file_type", "")
    title = Path(res.get("file") or "").name or _title(res)   # 조각 머리는 파일 이름(안정적인 식별자)
    chunks: list[dict] = []
    cur: list[Piece] = []

    def head_line(ps: list[Piece]) -> str:
        secs = list(dict.fromkeys(" > ".join(p.section_path) for p in ps if p.section_path))
        pages = [p.page for p in ps if p.page is not None]
        sheets = list(dict.fromkeys(p.sheet for p in ps if p.sheet))
        bits = [f"문서: {title}"]
        if secs:
            bits.append("위치: " + (secs[0] if len(secs) == 1 else f"{secs[0]} … {secs[-1]}"))
        if ft in ("pdf", "pptx") and pages:
            bits.append(f"p.{min(pages)}" + (f"–{max(pages)}" if max(pages) != min(pages) else ""))
        if ft in ("xlsx", "csv") and sheets:
            bits.append("시트: " + ", ".join(sheets))
        return "[" + " · ".join(bits) + "]"

    def body(ps: list[Piece]) -> str:
        lines, loc = [], None
        for p in ps:
            l = _locator(p.page, p.sheet, ft)
            if l and l != loc:
                lines.append(l)
                loc = l
            lines.append(p.text)
        return "\n\n".join(lines)

    def size(ps: list[Piece]) -> int:
        return len(head_line(ps)) + 2 + len(body(ps))

    def flush() -> None:
        if not cur:
            return
        text = head_line(cur) + "\n\n" + body(cur)
        pages = [p.page for p in cur if p.page is not None]
        chunks.append({"index": len(chunks), "text": text, "chars": len(text),
                       "section_paths": list(dict.fromkeys(tuple(p.section_path) for p in cur)),
                       "page_start": min(pages) if pages else None, "page_end": max(pages) if pages else None,
                       "sheets": list(dict.fromkeys(p.sheet for p in cur if p.sheet))})
        cur.clear()

    def parts_of(p: Piece) -> list[Piece]:
        """조각 하나에 혼자 들어가지 않는 블록을 나눈다."""
        if size([p]) <= max_chars:
            return [p]
        meta = dict(section_path=p.section_path, page=p.page, sheet=p.sheet)
        if p.table is not None:
            tm = p.table
            fixed_extra = size([Piece(tm.render([], [], part="(표 계속 999/999)"), **meta)])
            parts = []
            for cols in tm.column_groups(max_chars // 2):
                fixed = fixed_extra + len(tm.header(cols))
                g, used = [], fixed
                for r in range(len(tm.body)):
                    line = len(tm.row(r, cols)) + 1
                    if g and used + line > max_chars:
                        parts.append((g, cols))
                        g, used = [], fixed
                    g.append(r)
                    used += line
                parts.append((g, cols))
            n = len(parts)
            return [Piece(tm.render(rows, cols, part=f"(표 계속 {k + 1}/{n})" if n > 1 else ""), **meta)
                    for k, (rows, cols) in enumerate(parts)]
        room = max_chars - size([Piece("", **meta)]) - 2
        return [Piece(t, **meta) for t in _split_text(p.text, max(room, 1))]

    key = None
    for piece in _pieces(res):
        k = tuple(piece.section_path[:2])
        if cur and k != key and size(cur) >= max_chars // 4:
            flush()
        key = k
        for part in parts_of(piece):
            if cur and size(cur + [part]) > max_chars:
                flush()
            cur.append(part)
    flush()
    return chunks
