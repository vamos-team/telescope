"""표(병합 해제된 격자) → 긴 형식 셀 목록.

어떤 회사·문서든 표는 결국 "행 라벨 경로 × 열 머리글 경로 → 값"이다.
행/열이 뒤집힌 표(SKBB 형식 B), 머리글을 여러 줄로 쌓은 표(SKT XBRL 렌더러),
'기초 장부금액:' 같은 그룹 행이 있는 표(KT 형식 D), 들여쓰기로 계층을 표현한
엑셀(SKT IR) 모두 여기서 같은 셀 목록으로 펼쳐진다. 어느 축이 계정이고 어느
축이 증감 항목인지는 위치가 아니라 extract.py가 라벨을 사전과 대조해 정한다.

조용한 기본값 금지
- 단위를 못 찾으면 원 단위로 가정하지 않는다: value_base=None, note='unit_missing',
  표 경고에 기록 (과거 74,182청크 오염 사고의 원인이 "조용한 기본값"이었다).
- 엑셀 비율은 표시 형식(%)과 값 범위를 함께 보고 판단하고, 추정이 들어가면 note에 남긴다.
- 엑셀 표시 형식의 축소 배율(끝 콤마)은 셀마다 계산해 저장하고, 같은 행 안에서
  배율이 다르면 그 자리에서 표시한다 (KT 1,000배 사고 유형).
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

from . import hints as learned
from .model import RawCell
from .numbers import (clean, detect_period, detect_unit, is_money_unit, is_ratio_label,
                      normalize_kr_amount, parse_number, quantity_unit)

_TOTAL_RE = re.compile(r"(^|\s)(합\s*계|총\s*계|소\s*계|계|total|subtotal)(\s|$|\()", re.I)
_YEAR_ONLY = re.compile(r"^(19|20)\d{2}(\.0)?$")


def is_total_label(label: str) -> bool:
    return bool(_TOTAL_RE.search(clean(label)))


@dataclass
class Cell:
    r: int                        # 빈 행·열을 걷어낸 뒤의 행 번호 (표 안 순서)
    c: int
    row_path: list[str]
    row_label: str
    col_path: list[str]
    text: str
    value: Optional[float]
    value_base: Optional[float]   # 단위 배수 적용(원 기준). 단위 미상이면 None, 비율은 그대로
    unit: str
    period: str
    is_total: bool
    src_ref: str = ""             # 원본 좌표: '연결_IS!D14' / 'R6C9'
    number_format: str = ""
    display_scale: float = 1.0
    origin: str = "disclosed_code"
    note: str = ""                # unit_missing / pct_format / ratio_fraction_assumed / display_scale_mixed …


@dataclass
class TableAnalysis:
    n_rows: int
    n_cols: int
    header_rows: int
    label_cols: int
    unit: str
    multiplier: float
    unit_source: str              # context | header | learned | default | none
    period_hint: str
    caption: str
    cells: list[Cell] = field(default_factory=list)
    header_paths: list[list[str]] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    period_source: str = ""       # header | context | learned | none


def _cell_numeric(rc: RawCell) -> bool:
    if rc.number is not None:
        return True
    v, _ = parse_number(rc.text)
    return v is not None and any(ch.isdigit() for ch in rc.text)


def _is_header_like_numeric(rc: RawCell) -> bool:
    """머리글에 흔한 숫자(연도). 2024 / 2024.0"""
    t = clean(rc.text) if rc.number is None else str(rc.number)
    return bool(_YEAR_ONLY.match(t))


def _dedupe(parts: list[str]) -> list[str]:
    out: list[str] = []
    for p in parts:
        p = clean(p)
        if p and (not out or out[-1] != p) and p not in out:
            out.append(p)
    return out


def _trim(grid: list[list[RawCell]]) -> list[list[RawCell]]:
    if not grid:
        return grid
    n = max(len(r) for r in grid)
    grid = [r + [RawCell("")] * (n - len(r)) for r in grid]
    rows = [r for r in grid if any(clean(c.text) or c.number is not None for c in r)]
    if not rows:
        return []
    keep = [j for j in range(n) if any(clean(r[j].text) or r[j].number is not None for r in rows)]
    return [[r[j] for j in keep] for r in rows]


def _learned_period(label: str) -> str:
    return learned.load()["periods"].get(learned.key(label), "")


def analyze(grid: list[list[RawCell]], context: list[str] | None = None,
            default_unit: str = "") -> TableAnalysis:
    context = context or []
    grid = _trim(grid)
    if not grid:
        return TableAnalysis(0, 0, 0, 0, "", 1.0, "none", "", context[0] if context else "")
    n_rows, n_cols = len(grid), len(grid[0])

    # ── 1. 머리글 행 수: 첫 "데이터 행"(연도가 아닌 숫자 칸이 있는 행) 전까지
    header_rows = 0
    for i, row in enumerate(grid):
        data_nums = [c for c in row if _cell_numeric(c) and not _is_header_like_numeric(c)]
        if data_nums and not all(c.is_header for c in data_nums):
            header_rows = i
            break
    else:
        header_rows = min(1, n_rows)   # 숫자 없는 표: 첫 행만 머리글로
    flagged = 0                        # 원본이 머리글 표시를 줬으면(TH) 그 범위를 존중
    for row in grid:
        if all(c.is_header or not clean(c.text) for c in row):
            flagged += 1
        else:
            break
    if flagged and flagged <= header_rows + 1:
        header_rows = max(header_rows, flagged) if header_rows else flagged
    header_rows = min(header_rows, max(0, n_rows - 1))

    # ── 2. 라벨 열 수: 데이터 행에서 숫자 비율이 낮은 왼쪽 연속 열
    data = grid[header_rows:]
    label_cols = 0
    for j in range(n_cols):
        filled = [r[j] for r in data if clean(r[j].text) or r[j].number is not None]
        if not filled:
            if j == label_cols:
                label_cols += 1
                continue
            break
        num_ratio = sum(_cell_numeric(c) for c in filled) / len(filled)
        if num_ratio < 0.34:
            label_cols = j + 1
        else:
            break
    if label_cols >= n_cols:      # 전부 텍스트인 표
        label_cols = 1 if n_cols > 1 else 0

    # ── 3. 열 머리글 경로
    header_paths: list[list[str]] = []
    for j in range(n_cols):
        header_paths.append(_dedupe([grid[i][j].text for i in range(header_rows)]))

    # ── 4. 단위: 주변 문단 → 머리글 → 승인된 매핑 → (명시적으로 넘긴) 기본값. 그래도 없으면 미상
    lk = learned.load()["units"]
    unit, mult = detect_unit(context)
    unit_source = "context" if unit else "none"
    if not unit:
        hdr_texts = [" ".join(p) for p in header_paths] + [c.text for c in grid[0]]
        unit, mult = detect_unit(hdr_texts)
        unit_source = "header" if unit else "none"
    if not unit:
        for t in context + [" ".join(p) for p in header_paths]:
            if learned.key(t) in lk:
                unit, mult = detect_unit([f"단위: {lk[learned.key(t)]}"])
                unit_source = "learned" if unit else "none"
                break
    if not unit and default_unit:
        unit, mult = detect_unit([f"단위: {default_unit}"])
        unit_source = "default"

    # 기간 표지: 가장 가까운 문맥부터. '당기와 전기 중 …'처럼 둘 다 있는 문장은 근거 아님.
    # '1) 2021년' 같은 연도 표지도 인정(→ extract에서 회계연도와 비교해 당기/전기로 변환)
    period_hint = ""
    for t in context:
        tt = t.replace(" ", "")
        if "당기" in tt and "전기" in tt:
            continue
        p = detect_period(t)
        if p in ("current", "prior", "prior2") or re.fullmatch(r"20\d{2}", p or ""):
            period_hint = p
            break

    ta = TableAnalysis(n_rows, n_cols, header_rows, label_cols, unit, mult, unit_source,
                       period_hint, context[0] if context else "", header_paths=header_paths)
    if unit_source == "default":
        ta.warnings.append(f"단위를 찾지 못해 기본값 '{unit}' 적용 (unit_source=default) — 확인 필요")

    # ── 5. 행 경로: 라벨 열 + 그룹 행(값 없는 라벨 행) + 들여쓰기 계층
    row_label_units: dict[int, str] = {}       # 행 라벨이 직접 알려준 원화 아닌 단위 (행 번호 → 단위)
    group_stack: list[tuple[int, str]] = []    # 값 없는 그룹 행 (indent, label)
    data_stack: list[tuple[int, str]] = []     # 들여쓰기 부모가 되는 데이터 행
    any_period = False
    unit_missing = 0
    for i in range(header_rows, n_rows):
        row = grid[i]
        labels = _dedupe([row[j].text for j in range(label_cols)])
        indent = max((row[j].indent for j in range(label_cols)), default=0)
        value_cells = [(j, row[j]) for j in range(label_cols, n_cols)
                       if clean(row[j].text) or row[j].number is not None]
        has_num = any(_cell_numeric(c) for _, c in value_cells)
        leaf = labels[-1] if labels else ""

        if not has_num and leaf and not value_cells:
            # 값이 빈 행이 전부 그룹 제목은 아니다(예: 해당 연도 0건이라 비운 '손상차손' 행).
            # 그룹으로 보는 경우: 라벨이 ':'로 끝남 / 원본이 머리글 표시 / 다음 행이 더 깊게 들여씀
            nxt = next((grid[k] for k in range(i + 1, n_rows)
                        if any(clean(x.text) for x in grid[k])), None)
            nxt_indent = max((nxt[j].indent for j in range(label_cols)), default=0) if nxt else 0
            is_group = (leaf.rstrip().endswith((":", "：")) or nxt_indent > indent
                        or all(row[j].is_header for j in range(label_cols) if clean(row[j].text)))
            if not is_group:
                continue
            while group_stack and group_stack[-1][0] >= indent:
                group_stack.pop()
            group_stack.append((indent, leaf))
            data_stack.clear()
            continue
        while data_stack and data_stack[-1][0] >= indent:
            data_stack.pop()
        parents = [g for _, g in group_stack] + [d for _, d in data_stack]
        parents = [p for p in parents if p not in labels]
        if leaf:
            data_stack.append((indent, leaf))
        row_path = _dedupe(parents + labels)
        is_total = is_total_label(leaf)
        row_unit, row_mult = detect_unit([leaf]) if leaf else ("", 1.0)   # '매출(백만원)'
        row_qty = "" if row_unit else quantity_unit(leaf)                  # '배출권 수량', '주식수'
        ratio_row = is_ratio_label(leaf)
        if (row_unit and not is_money_unit(row_unit)) or row_qty:
            row_label_units[i] = row_unit or row_qty

        row_cells: list[Cell] = []
        for j, rc in value_cells:
            note = ""
            if rc.number is not None:
                v, pct = rc.number, ""
            else:
                v, pct = parse_number(rc.text)
                if v is None:
                    # '1,200억원'처럼 단위가 칸 안에 붙은 금액
                    amt = normalize_kr_amount(rc.text)
                    if len(amt) == 1 and amt[0]["text"].replace(" ", "") == clean(rc.text).replace(" ", ""):
                        a = amt[0]
                        col_path = header_paths[j]
                        row_cells.append(Cell(
                            r=i, c=j, row_path=row_path, row_label=leaf, col_path=col_path,
                            text=clean(rc.text), value=a["value"],
                            value_base=a["value"] if a["kind"] == "KRW" else a["value"],
                            unit="원" if a["kind"] == "KRW" else a["kind"], period="", is_total=is_total,
                            src_ref=rc.ref, number_format=rc.number_format,
                            display_scale=rc.display_scale, origin=rc.origin, note="inline_unit"))
                    continue
            col_path = header_paths[j]
            # 단위 우선순위: 칸의 % 표기 → 열 머리글('금액(백만원)') → 행 라벨('매출(백만원)') → 표 단위
            col_leaf = col_path[-1] if col_path else ""
            cu, cm = detect_unit([" ".join(col_path)]) if col_path else ("", 1.0)
            if cu:
                u, m = cu, cm
            elif row_unit:
                u, m = row_unit, row_mult
            elif quantity_unit(col_leaf) or row_qty:
                # 라벨이 금액이 아니라 수량·주식수를 센다고 말해 주는 칸 (표 단위가 백만원이어도 따르지 않는다)
                u, m, note = quantity_unit(col_leaf) or row_qty, 1.0, "quantity_label"
            else:
                u, m = unit, mult
            fmt_pct = "%" in (rc.number_format or "")
            if pct:
                u, m = pct, 1.0
            elif fmt_pct and rc.number is not None:
                v, u, m, note = v * 100.0, "%", 1.0, "pct_format"       # 0.057 (서식 0.0%) → 5.7%
            elif (ratio_row or is_ratio_label(col_leaf)) and u not in ("%", "%p"):
                u, m = "%", 1.0
                if rc.number is not None and abs(v) <= 1.0 and v != 0:
                    v, note = v * 100.0, "ratio_fraction_assumed"        # 서식 없음 + 1 이하 → 소수로 추정(표시)
                else:
                    note = "ratio_label"
            if not u:
                unit_missing += 1
                note = note or "unit_missing"
                vb: Optional[float] = None
            elif u in ("%", "%p"):
                vb = v
            else:
                vb = v * m
            # 기간은 열 머리글 우선. 행 라벨은 '전기초'·'2024년' 같은 짧은 라벨만 본다
            period = ""
            for part in col_path + [p for p in row_path if len(clean(p)) <= 8]:
                period = detect_period(part) or _learned_period(part)
                if period:
                    break
            any_period = any_period or bool(period)
            row_cells.append(Cell(
                r=i, c=j, row_path=row_path, row_label=leaf, col_path=col_path,
                text=clean(rc.text) if rc.number is None else str(rc.number),
                value=v, value_base=vb, unit=u, period=period or period_hint, is_total=is_total,
                src_ref=rc.ref, number_format=rc.number_format, display_scale=rc.display_scale,
                origin=rc.origin, note=note))
        # 같은 행 안에서 화면 표시 배율이 다르면 읽는 순간 표시
        scales = {c.display_scale for c in row_cells if c.number_format}
        if len(scales) > 1:
            for c in row_cells:
                c.note = (c.note + ";" if c.note else "") + "display_scale_mixed"
            ta.warnings.append(
                f"행 '{leaf}': 같은 행에서 표시 배율이 다름 {sorted(scales)} "
                f"({', '.join(c.src_ref for c in row_cells)}) — 원본 값 확인 필요")
        ta.cells.extend(row_cells)

    # 표 단위가 없는데 두 행 이상이 같은 '원화 아닌 단위'를 라벨로 밝힌 표(예: 첫 행·끝 행이 '…수량'인
    # 증감표)는 단위 없는 나머지 행도 그 단위로 본다. 원화 단위는 이렇게 물려주지 않는다(틀리면 값이 어긋난다).
    if not ta.unit and len(row_label_units) >= 2 and len(set(row_label_units.values())) == 1:
        inherit = next(iter(row_label_units.values()))
        for c in ta.cells:
            if c.note.startswith("unit_missing"):
                c.unit, c.value_base, c.note = inherit, c.value, "unit_inherited_rows"
                unit_missing -= 1
    ta.period_source = "header" if any_period else ("context" if period_hint else "none")
    if unit_missing:
        ta.warnings.append(f"단위를 찾지 못한 값 {unit_missing}개 — 원 단위로 가정하지 않고 value_base를 비움")
    return ta
