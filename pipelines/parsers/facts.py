"""사실 추출 — parse() 결과의 표에서 장부금액 증감표·잔액표를 찾아 "사실" 한 줄씩으로 정리하고 검산한다.

    from parsers import parse
    from parsers.facts import extract, report_period
    res = parse("LGU_3분기보고서.xml")
    report_period(res)       # → {"fiscal_year": 2026, "report_code": "11014", "period_end": "2026-09-30"}
    extract(res)             # → [{"kind": "movement", "account": "유형자산", "member": "토지", "item": "기초",
                             #     "period": "current", "value": 919615000000, "value_eok": 9196.15,
                             #     "status": "verified", ...}, ...]

회사·연도별 형식 분기 없이 한 규칙으로 처리한다.
1. 표 고르기: 항목 축에 기초·기말이 모두 있는 표만 증감표로 본다.
2. 축 판별: 행 라벨과 열 머리글 중 항목 사전(ITEMS)에 더 많이 맞는 쪽이 항목 축.
   SK텔레콤(항목=행)과 SK브로드밴드(항목=열) 같은 방향 차이를 코드 분기 없이 흡수한다.
3. 계정: 표 캡션 → 가까운 제목 순으로 계정 사전(ACCOUNTS).
4. 자산 종류(member): 반대 축의 경로에서 자산 사전(MEMBERS). 합계면 ''.
   모든 열에 똑같이 붙은 머리글('측정 전체 > 취득원가')은 구분 정보가 아니라서 무시한다.
5. 측정 열·행(총장부금액·누계액)은 증감 항목이 아니라 잔액 구성요소 → kind="balance"로 따로 낸다.
6. 검산
   - 증감: 기초 + Σ증감 = 기말. 사전에 없는 행도 합에 넣어 빠진 항목이 드러나게 한다.
     행 하나를 빼면 정확히 맞으면 그 행은 위 항목의 내역 행(status="breakdown").
     감소 항목을 양수로 적은 표는 검산으로 확인될 때만 부호를 음수로 맞춘다.
   - 잔액: 총액 + 누계액(+손상·보조금) = 순액.
숫자는 셀 값만 쓴다. 단위를 못 찾은 값은 환산하지 않고 status="unit_missing".

7. XBRL 이름표 확인 (xbrl=[zip 경로…]를 넘길 때): 증감표의 기초·기말 값과 같은 XBRL 사실 중 문서 안 후보
   (장 제목의 연결/별도, 표 위 '당기/전기', 기대 날짜)와 맞는 것을 모아 표 단위로 연결/별도,
   자산 종류 축 단위로 기간·자산 종류를 다수결로 정해 다시 붙이고 다시 검산한다. 판정 단계 없음.
   같은 공시의 XBRL과 그 전에 제출된 정기보고서 XBRL을 넘기면 된다(파일은 부르는 쪽이 고른다).
8. XBRL로 확인되지 않은 표(XBRL이 없으면 전부): 장 제목·표 위 문구로 연결/별도·기간, 열 사이 합계 관계로
   소계·합계 열을 정한다(label_source="document").
"""
from __future__ import annotations

import bisect
import re
import zipfile
from collections import Counter, defaultdict
from datetime import date
from types import SimpleNamespace
from typing import Optional

from .numbers import detect_period, is_money_unit, unspace
from .tables import is_total_label


def _c(d: dict) -> list[tuple[str, list[re.Pattern]]]:
    return [(k, [re.compile(p, re.I) for p in v]) for k, v in d.items()]


# ─── 사전 (위에서부터 먼저 맞는 것이 채택된다) ───────────────────────────

ACCOUNTS = _c({
    "사용권자산": ["사용권자산", r"^\d{1,2}\s*\.\s*리스$"],
    "투자부동산": ["투자부동산"],
    # 영업권은 무형자산과 별도 계정. '무형자산 및 영업권' 캡션은 무형자산이므로 영업권 단독 제목만
    "영업권": [r"^\s*(\d{1,2}\s*\.\s*)?영업권(\s*\(연결\))?\s*$", r"영업권\s*(장부금액)?의?\s*(변동|증감)"],
    "무형자산": ["무형자산"],
    "유형자산": ["유형자산"],
})
ITEMS = _c({
    "기초": [r"^(당|전|전전)?\s*(기|분기|반기)?\s*초(\s|$|\(|,)", "기초", "beginning", "opening"],
    "기말": [r"^(당|전|전전)?\s*(기|분기|반기)?\s*말(\s|$|\(|,)", "기말", "ending", "closing"],
    "손상차손환입": [r"손상\s*차손\s*환입", "^환입", "reversal"],
    "손상차손": [r"손상(?!.*누계)"],
    "감가상각비": [r"감가\s*상각(?!.*누계)", "상각비", r"^상각(?!.*누계|.*완료)", "depreciation", "amortization"],
    "사업결합": [r"사업\s*결합(?!을\s*통한\s*취득\s*이외)", "합병", r"영업\s*양수"],
    "연결범위변동": [r"연결\s*범위", r"지배력\s*상실", r"종속기업.*(처분|제외|매각)", r"영업\s*양도"],
    "매각예정대체": [r"매각\s*예정", "처분자산집단"],
    "처분": ["처분", "폐기", "매각", "disposal"],
    "정부보조금": [r"정부\s*보조금"],
    "외화환산": ["환율", r"외화\s*환산", r"환산\s*차"],
    "재평가": ["재평가", r"공정가치\s*평가"],
    "대체": ["대체", "재분류", "transfer", r"본\s*계정"],
    "취득": ["취득", "증가", "매입", r"자본적\s*지출", r"외부\s*취득", "acquisition", "addition", "신규"],
    "기타": [r"^기타(\s*(증감|변동|증가\(감소\)))?$", r"기타\s*변동", r"^기타\s*\(", "others?$"],
})
MEMBERS = _c({
    "토지": ["^토지"], "건물": ["^건물"], "구축물": ["구축물"], "기계장치": [r"기계\s*장치"],
    "차량운반구": ["차량"], "공기구비품": ["공기구", "비품", "집기"], "사용권자산": [r"사용권\s*자산"],
    "건설중인자산": [r"건설\s*중인\s*(유형)?자산", "미착"], "건설중인무형자산": [r"건설\s*중인\s*무형"],
    "기타유형자산": [r"기타\s*(의\s*)?유형\s*자산"], "영업권": [r"^영업권(\s*합계)?$"], "주파수이용권": ["주파수"],
    "소프트웨어": [r"소프트\s*웨어", "software"], "개발비": ["개발비"], "산업재산권": [r"산업\s*재산권", "특허"],
    "회원권": ["회원권"], "고객관계": [r"고객\s*관계"], "기타무형자산": [r"기타\s*(의\s*)?무형\s*자산"],
    "임대주택": ["임대"], "기타": ["^기타$", r"^기타\s*자산"],
})
# 합계 라벨 → member '' (집계). 계정명 자체만 있는 열도 집계로 본다
MEMBER_TOTAL = [re.compile(p, re.I) for p in (r"이외의\s*무형\s*자산$", r"합\s*계", "^계$", r"총\s*계", "total",
                                               r"^(유형|무형)\s*자산$", r"^투자\s*부동산$")]
# 같은 자산에 측정 열이 여럿이면(총장부금액·누계액·순액) 증감에는 순장부금액만 쓴다
MEASURE_DROP = [re.compile(p, re.I) for p in (r"총\s*장부\s*금액", r"취득\s*원가", r"감가\s*상각\s*누계", r"상각\s*누계",
                                               r"손상\s*차손\s*누계", r"손상\s*누계", r"정부\s*보조금\s*(누계)?$")]
MEASURES = _c({
    "gross": [r"총\s*장부\s*금액", r"취득\s*원가"],
    "accum_dep": [r"감가\s*상각\s*누계", r"상각\s*누계"],
    "accum_impairment": [r"손상\s*차손\s*누계", r"손상\s*누계"],
    "gov_grant": [r"정부\s*보조금"],
    "net": [r"^(순\s*)?장부\s*(금|가)액(\s*합계)?$", "^순액$"],
})
_ACCT_NAMES = {a for a, _ in ACCOUNTS}
_MEASURE_WORDS = re.compile(r"^(순\s*)?(장부\s*)?금액(\s*합계)?$|^(당기|전기)$|단위\s*[:：]|^공시\s*금액$")
_DECREASE_ITEMS = {"감가상각비", "손상차손", "처분", "매각예정대체"}
_END = {("current", "close"): "current_end", ("current", "open"): "prior_end",
        ("prior", "close"): "prior_end", ("prior", "open"): "prior2_end", ("prior2", "close"): "prior2_end"}


# ─── 보고 기간 (정기보고서 표지) ────────────────────────────────────

_D = r"(20\d{2})\s*(?:년|[.\-/])\s*(\d{1,2})\s*(?:월|[.\-/])\s*(\d{1,2})\s*일?"
_COVER = re.compile(rf"사업연도\s*{_D}\s*부터\s*(?:사업연도\s*)?{_D}\s*까지")


def report_period(result: dict) -> dict:
    """정기보고서 표지 "사업연도 2026년 01월 01일 부터 2026년 09월 30일 까지" → 사업연도·보고서 코드·기간 말.
    보고서 종류는 기간 길이로 정한다(3·6·9·12개월 → 1분기·반기·3분기·사업). 못 찾으면 {}."""
    parts, n = [], 0
    tables = {t["table_no"]: t for t in result.get("tables", [])}
    for b in result.get("blocks", []):
        t = b.get("text") or ""
        if b.get("kind") == "table" and b.get("table_no") in tables:
            t = " ".join(c.get("text") or "" for c in tables[b["table_no"]]["cells"][:60])
        if t:
            parts.append(t)
            n += len(t)
        if n > 4000:
            break
    m = _COVER.search("\n".join(parts))
    if not m:
        return {}
    y0, m0, y1, mo = int(m.group(1)), int(m.group(2)), int(m.group(4)), int(m.group(5))
    rc = {3: "11013", 6: "11012", 9: "11014", 12: "11011"}.get((y1 - y0) * 12 + mo - m0 + 1)
    if not rc:
        return {}
    return {"fiscal_year": y0, "report_code": rc, "period_end": f"{y1}-{mo:02d}-{int(m.group(6)):02d}"}


# ─── 라벨 판정 ─────────────────────────────────────────────────

def _norm(s: str) -> str:
    s = unspace(s)
    s = re.sub(r"\(\*\d*(,\s*\d+)*\)|\(주\d*\)|\*\d*", "", s)   # 각주 표시 제거
    return s.strip(" :：,")


def _first(vocab, text: str) -> Optional[str]:
    t = _norm(text)
    if not t:
        return None
    for key, pats in vocab:
        if any(p.search(t) for p in pats):
            return key
    return None


def _match_item(parts: list[str]) -> Optional[str]:
    for p in reversed(parts):             # 가장 구체적인(마지막) 라벨부터
        k = _first(ITEMS, p)
        if k:
            return k
    return None


def _member_of(parts: list[str]) -> tuple[str, str]:
    """반대 축 경로 → (member, method). member '' = 집계. 가장 깊은 의미 있는 라벨이 정한다.
    ['유형자산','토지','장부금액 합계'] → '토지'(vocab), ['유형자산 합계'] → ''(total), 사전에 없으면 원문(raw)."""
    meaningful = [p for p in parts
                  if _norm(p) and not any(m.search(_norm(p)) for m in MEASURE_DROP)
                  and not _MEASURE_WORDS.search(_norm(p))
                  and not (len(_norm(p)) <= 8 and detect_period(_norm(p)))]     # '당기말'·'2024년' 같은 기간 머리글
    for p in reversed(meaningful):
        n = _norm(p)
        k = _first(MEMBERS, p)
        if k:
            return k, "vocab"
        if n in _ACCT_NAMES:
            continue
        if any(m.search(n) for m in MEMBER_TOTAL):
            return "", "total"
        return n, "raw"
    return "", "implicit_total"


def _account_of(context: list[str], section_path: list[str], caption: str) -> Optional[str]:
    # 캡션 → 소속 제목(가까운 것부터) → 각주가 아닌 문맥. 각주는 다른 계정을 언급하는 경우가 많다
    ctx = [t for t in context if not re.match(r"^\s*[(（]\s*(주|\*)", t or "")]
    for t in [caption] + list(reversed(section_path)) + ctx:
        k = _first(ACCOUNTS, t or "")
        if k:
            return k
    return None


def _period_of(cell_period: str, hint: str, fiscal_year: Optional[int]) -> str:
    p = cell_period or hint
    if p in ("current", "prior", "prior2"):
        return p
    if re.fullmatch(r"20\d{2}", p or "") and fiscal_year:
        return {0: "current", 1: "prior", 2: "prior2"}.get(fiscal_year - int(p), p)
    return p or "current"


def _measure_of(parts: list[str]) -> Optional[str]:
    """측정 구분: gross / accum_dep / accum_impairment / gov_grant / net. 해당 없으면 None."""
    for p in reversed(parts):
        k = _first(MEASURES, p)
        if k:
            return k
    return None


def _member_two(other_parts: list[str], measure_parts: list[str]) -> tuple[str, str]:
    """잔액표의 자산 종류: 측정 구분의 반대 축을 먼저, 없으면 측정 축 머리글에서."""
    m1, k1 = _member_of(other_parts)
    m2, k2 = _member_of(measure_parts)
    for m, k, want in ((m1, k1, "vocab"), (m2, k2, "vocab"), (m1, k1, "raw"), (m2, k2, "raw")):
        if k == want:
            return m, k
    return "", "total"


# ─── 추출 ─────────────────────────────────────────────────────

def _fact(t, kind, period, account, member, item, c, label, method, status, note) -> dict:
    v = c.value_base
    if v is None and status != "breakdown":
        status = "unit_missing"
        note = (note + "; " if note else "") + "단위를 찾지 못해 환산하지 않음"
    return {"kind": kind, "scope": "", "label_source": "", "period": period, "account": account, "member": member,
            "item": item,
            "value": v, "value_eok": v / 1e8 if (v is not None and is_money_unit(c.unit)) else None,
            "unit": c.unit, "status": status, "note": note, "method": method, "label": _norm(label),
            "table_no": t.table_no, "r": c.r, "c": c.c, "src_ref": c.src_ref, "page": t.page, "sheet": t.sheet,
            "section": " > ".join(t.section_path)}


def _balance_groups(groups: dict, t, account: str, method: str) -> list[dict]:
    out = []
    for (member, mmethod, end), ms in groups.items():
        if "net" not in ms or len(ms) < 2:
            continue
        net = ms["net"][0].value
        comp = sum(c.value for k, (c, _) in ms.items() if k != "net")
        ok = abs(comp - net) <= max(2.0, abs(net) * 0.001)
        if not ok and set(ms) == {"gross", "net"}:
            continue      # 누계액 없이 총액≠순액: 잔액표가 아니다(양도 내역·범위 공시 등)
        for k, (c, label) in ms.items():
            out.append(_fact(t, "balance", end, account, member, k, c, label, f"{method};member={mmethod}",
                             "verified" if ok else "mismatch", "" if ok else f"구성요소 합={comp:,.0f} vs 순액={net:,.0f}"))
    return out


def _balance_openclose(t, fy, account: str, hits: dict, item_axis: str) -> list[dict]:
    """증감 항목 없이 '기초 | 기말' 두 칸만 있는 표: (자산, 측정) × (기초, 기말)."""
    groups: dict[tuple, dict] = defaultdict(dict)
    for c in t.cells:
        idx, other = (c.r, c.col_path) if item_axis == "row" else (c.c, c.row_path)
        item = hits.get(idx)
        if item not in ("기초", "기말"):
            continue
        ms = _measure_of(other)
        if not ms:
            continue
        member, mmethod = _member_of(other)
        end = _END.get((_period_of("", t.period_hint, fy), "open" if item == "기초" else "close"))
        if end:
            groups[(member, mmethod, end)].setdefault(ms, (c, " > ".join(other)))
    return _balance_groups(groups, t, account, f"balance_openclose;axis={item_axis}")


def _balance_table(t, fy) -> list[dict]:
    """잔액표: 자산 × (취득원가 | 누계액 | 장부금액). 측정 구분이 열에 있든 행에 있든 같은 규칙."""
    col_ms = {c.c: _measure_of(c.col_path) for c in t.cells}
    row_ms = {c.r: _measure_of(c.row_path[-1:]) for c in t.cells}
    if {"gross", "net"} <= set(col_ms.values()):
        axis = "col"
    elif {"gross", "net"} <= set(row_ms.values()):
        axis = "row"
    else:
        return []
    account = _account_of(t.context, t.section_path, t.caption)
    if not account:
        return []
    groups: dict[tuple, dict] = defaultdict(dict)
    for c in t.cells:
        ms = col_ms[c.c] if axis == "col" else row_ms[c.r]
        if not ms:
            continue
        other, mpath = (c.row_path, c.col_path) if axis == "col" else (c.col_path, c.row_path)
        member, mmethod = _member_two(other, mpath)
        end = {"current": "current_end", "prior": "prior_end", "prior2": "prior2_end"}.get(
            _period_of(c.period, t.period_hint, fy))
        if end:
            groups[(member, mmethod, end)].setdefault(ms, (c, " > ".join(c.col_path) if axis == "col" else c.row_label))
    return _balance_groups(groups, t, account, f"balance_table;axis={axis}")


def extract(result: dict, fiscal_year: Optional[int] = None, xbrl: Optional[list] = None) -> list[dict]:
    """parse() 결과 → 증감(kind="movement")·잔액(kind="balance") 사실 목록.

    fiscal_year: 머리글이 '2025년'처럼 연도로 적힌 표를 당기/전기로 바꿀 때 쓴다.
                 주지 않으면 정기보고서 표지에서 읽고(report_period), 못 읽으면 연도를 그대로 둔다.
    xbrl: 같은 공시·이전 정기보고서의 XBRL zip 경로 목록. 주면 증감표 사실의 연결/별도·기간·자산 종류를
          XBRL 값으로 확인해 다시 붙인다(label_source="xbrl"). 없으면 scope(연결/별도)는 "".
    """
    period = report_period(result)
    fy = fiscal_year or period.get("fiscal_year")
    facts: list[dict] = []
    for td in result.get("tables", []):
        if not td.get("cells"):
            continue
        t = SimpleNamespace(**{k: v for k, v in td.items() if k != "cells"})
        t.cells = [SimpleNamespace(**c) for c in td["cells"]]
        facts.extend(_extract_table(t, fy))
    if xbrl and period.get("period_end"):
        _confirm_with_xbrl(facts, result, [_instance(p) for p in xbrl], date.fromisoformat(period["period_end"]))
    _label_from_document(facts, result)
    return facts


def _extract_table(t, fy) -> list[dict]:
    facts: list[dict] = []

    def component(parts: list[str]) -> bool:          # 측정 구성 행·열(기초취득원가·감가상각누계액 등)
        leaf = _norm(parts[-1]) if parts else ""
        return bool(leaf) and any(m.search(leaf) for m in MEASURE_DROP)

    row_hits: dict[int, str] = {}
    col_hits: dict[int, str] = {}
    comp_rows: set[int] = set()
    comp_cols: set[int] = set()
    for c in t.cells:
        if c.r not in row_hits and c.r not in comp_rows:
            if component(c.row_path):
                comp_rows.add(c.r)
            elif (k := _match_item(c.row_path)):
                row_hits[c.r] = k
        if c.c not in col_hits and c.c not in comp_cols:
            if component(c.col_path):
                comp_cols.add(c.c)
            elif (k := _match_item(c.col_path)):
                col_hits[c.c] = k
    row_ok = {"기초", "기말"} <= set(row_hits.values())
    col_ok = {"기초", "기말"} <= set(col_hits.values())
    if not (row_ok or col_ok):
        return _balance_table(t, fy)          # 증감표가 아니어도 한 축이 측정 구분이면 잔액표
    if row_ok and col_ok:
        item_axis = "row" if len(set(row_hits.values())) >= len(set(col_hits.values())) else "col"
    else:
        item_axis = "row" if row_ok else "col"
    hits = row_hits if item_axis == "row" else col_hits
    account = _account_of(t.context, t.section_path, t.caption)
    if not account:
        return []
    if not set(hits.values()) - {"기초", "기말"}:
        return _balance_openclose(t, fy, account, hits, item_axis)

    def item_side(c):
        if item_axis == "row":
            return c.r, c.row_path, c.col_path, c.row_label
        return c.c, c.col_path, c.row_path, " > ".join(c.col_path)

    # 모든 자산 경로에 똑같이 붙은 머리글은 구분 정보가 아니다
    paths = {tuple(item_side(c)[2]) for c in t.cells}
    common: set[str] = set()
    if len(paths) > 1:
        common = set(next(iter(paths)))
        for p in paths:
            common &= set(p)
    comp_idx = comp_rows if item_axis == "row" else comp_cols
    open_idx = min((i for i, k in hits.items() if k == "기초"), default=None)
    # 기말이 두 번 나오는 표(증감 끝 + 구성요소 뒤)가 있어, 경계는 기초 뒤 '첫' 기말
    close_idx = min((i for i, k in hits.items() if k == "기말" and (open_idx is None or i > open_idx)), default=None)

    cand, has_net = [], set()
    bal: dict[tuple, dict] = defaultdict(dict)
    for c in t.cells:
        idx, item_parts, _, label = item_side(c)
        mparts = [p for p in item_side(c)[2] if p not in common]
        member, mmethod = _member_of(mparts)
        period = _period_of(c.period, t.period_hint, fy)
        col_measure = _measure_of(mparts)
        dropped = any(m.search(_norm(p)) for p in mparts for m in MEASURE_DROP)
        if idx in comp_idx:
            # 구성요소가 항목 축에 있는 표: 기초 앞이면 기초 잔액, 기말 뒤면 기말 잔액의 구성
            if open_idx is not None and close_idx is not None:
                side = "open" if idx < open_idx else ("close" if idx > close_idx else None)
                ms = _measure_of(item_parts)
                if side and ms and not dropped:
                    bal[(member, mmethod, period, side)].setdefault(ms, (c, label))
            continue
        item = hits.get(idx)
        if item in ("기초", "기말"):
            bal[(member, mmethod, period, "open" if item == "기초" else "close")].setdefault(col_measure or "net", (c, label))
        if not dropped:
            has_net.add(f"{member}|{period}")
        cand.append((c, item, idx, member, mmethod, dropped, period, label))

    rows_by_key: dict[tuple, list] = defaultdict(list)
    for c, item, idx, member, mmethod, dropped, period, label in cand:
        if dropped and f"{member}|{period}" in has_net:
            continue          # 같은 자산에 순액 열이 있으면 총액·누계액 열은 증감에서 제외
        rows_by_key[(member, period)].append((idx, item, c, mmethod, label))

    # 증감 검산: 기초 + Σ(기초·기말 사이 항목) = 기말
    for (member, period), lst in rows_by_key.items():
        lst.sort(key=lambda x: x[0])
        open_v = next((x[2].value for x in lst if x[1] == "기초"), None)
        close_v = next((x[2].value for x in lst if x[1] == "기말"), None)
        status, note, breakdown_idx, flipped = "unchecked", "", None, set()
        if open_v is not None and close_v is not None:
            i0 = next(x[0] for x in lst if x[1] == "기초")
            i1 = next(x[0] for x in lst if x[1] == "기말")
            # 항목 라벨 자체가 소계('증감 계')인 것만 뺀다. 자산이 합계인 열은 빼면 안 된다
            mid = [x for x in lst if i0 < x[0] < i1 and not is_total_label(x[4].split(" > ")[-1])]
            s = open_v + sum(x[2].value for x in mid)
            if abs(s - close_v) <= max(2.0, abs(close_v) * 0.001):
                status = "verified"
            else:
                # 행 하나를 빼면 정확히 맞는가 → 그 행은 위 항목의 내역 행(이중 계산)
                exact = max(2.0, float(len(mid)))
                hit = [x for x in mid if x[2].value != 0 and abs((s - x[2].value) - close_v) <= exact]
                # 감소 항목을 양수로 적는 표: 양수인 감소 항목을 빼면 맞는가
                dec = [x for x in mid if x[1] in _DECREASE_ITEMS and x[2].value > 0]
                if len(hit) == 1:
                    breakdown_idx, status = hit[0][0], "verified"
                    note = f"'{_norm(hit[0][4])}'은 내역 행으로 판단해 합산에서 제외"
                elif dec and abs(s - 2 * sum(x[2].value for x in dec) - close_v) <= exact:
                    status, flipped = "verified", {x[0] for x in dec}
                    note = "원문이 감소 항목을 양수로 표기 — 부호를 음수로 맞춤"
                else:
                    status = "mismatch"
                    note = f"기초+증감={s:,.0f} vs 기말={close_v:,.0f} (원문 단위 {lst[0][2].unit or '미상'})"
        for idx, item, c, mmethod, label in lst:
            st, nt = status, note
            if idx == breakdown_idx:
                st, nt = "breakdown", "위 항목의 내역 행 — 합산 시 제외"
            if idx in flipped:      # 검산으로 확인된 경우에만 부호를 바꾼다
                c = SimpleNamespace(**{**vars(c), "value": -c.value,
                                       "value_base": None if c.value_base is None else -c.value_base})
            facts.append(_fact(t, "movement", period, account, member, item or _norm(label), c, label,
                               f"axis={item_axis};item={'vocab' if item else 'raw'};member={mmethod}", st, nt))

    # 잔액: 총액 + 누계액(+손상·보조금) = 순액
    for (member, mmethod, period, side), ms in bal.items():
        if "net" not in ms or len(ms) < 2:
            continue          # 구성요소가 없는 표는 잔액 사실을 만들지 않는다(순액은 증감의 기초·기말에 있음)
        end = _END.get((period, side))
        if not end:
            continue
        net = ms["net"][0].value
        comp_sum = sum(c.value for k, (c, _) in ms.items() if k != "net")
        ok = abs(comp_sum - net) <= max(2.0, abs(net) * 0.001)
        for k, (c, label) in ms.items():
            facts.append(_fact(t, "balance", end, account, member, k, c, label, f"axis={item_axis};member={mmethod}",
                               "verified" if ok else "mismatch", "" if ok else f"구성요소 합={comp_sum:,.0f} vs 순액={net:,.0f}"))
    return facts


# ─── XBRL 이름표 확인 ───────────────────────────────────────────

_XI = "{http://www.xbrl.org/2003/instance}"
_XD = "{http://xbrl.org/2006/xbrldi}"
_LB = "{http://www.xbrl.org/2003/linkbase}"
_XL = "{http://www.w3.org/1999/xlink}"
_SCOPE_AX = "ifrs-full:ConsolidatedAndSeparateFinancialStatementsAxis"
_MEASURE_AX = "ifrs-full:CarryingAmountAccumulatedDepreciationAmortisationAndImpairmentAndGrossCarryingAmountAxis"
_SCOPE_OF = {"ifrs-full:ConsolidatedMember": "consolidated", "ifrs-full:SeparateMember": "separate"}
_GENERIC = {"ifrs-full:PropertyPlantAndEquipmentMember", "ifrs-full:AtCostMember"}  # 다른 member와 함께면 구체성 없음
_SMALL_VALUE = 1e8    # 원. 이보다 작은 값은 여러 주석에 흔해 표 확인 근거로 세지 않는다
_BIG_VALUE = 1e9      # 원. 이만한 금액이 같은 연결/별도·같은 날짜로 우연히 일치할 가능성은 낮다
_MIN_SUPPORT = 2
_PERIOD_WORD = re.compile(r"(당|전전|전)\s*(분기|반기|기)(말)?")
_CHAPTER = re.compile(r"^\d+\.\s*(연결)?\s*재무제표(\s*주석)?$")
_instances: dict = {}


class _Instance:
    """XBRL 인스턴스 하나: 값(원) → [{scope, instant, members, concept}] + 한글 라벨."""

    def __init__(self, path):
        from lxml import etree
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            inst = next(n for n in names if n.endswith(".xbrl"))
            lab = next((n for n in names if re.search(r"lab-ko\.xml$|lab_.*-ko_.*\.xml$", n)), None)
            self.label = self._labels(z.read(lab)) if lab else {}
            t = etree.fromstring(z.read(inst), etree.XMLParser(recover=True, huge_tree=True))
        ctx = {}
        for c in t.iter(_XI + "context"):
            dims = {m.get("dimension"): (m.text or "").strip() for m in c.iter(_XD + "explicitMember")}
            i = c.find(f"{_XI}period/{_XI}instant")
            ctx[c.get("id")] = (dims, i.text.strip() if i is not None and i.text else None)
        self.by_val: dict[int, list[dict]] = defaultdict(list)
        for e in t:
            if not isinstance(e.tag, str) or e.get("unitRef") is None or e.get("contextRef") not in ctx:
                continue
            dims, instant = ctx[e.get("contextRef")]
            if not instant:
                continue
            try:
                v = round(float(e.text))
            except (TypeError, ValueError):
                continue
            self.by_val[v].append({
                "scope": _SCOPE_OF.get(dims.get(_SCOPE_AX), ""), "instant": instant,
                "members": tuple(sorted((a, m) for a, m in dims.items() if a not in (_SCOPE_AX, _MEASURE_AX))),
                "concept": f"{e.prefix}_{etree.QName(e).localname}"})
        self._keys = sorted(self.by_val)

    @staticmethod
    def _labels(raw: bytes) -> dict:
        from lxml import etree
        t = etree.fromstring(raw, etree.XMLParser(recover=True, huge_tree=True))
        out = {}
        for link in t.iter(_LB + "labelLink"):
            lc = {x.get(_XL + "label"): x.get(_XL + "href").split("#")[-1] for x in link.iter(_LB + "loc")}
            lab = {x.get(_XL + "label"): (x.get(_XL + "role") or "", (x.text or "").strip()) for x in link.iter(_LB + "label")}
            for a in link.iter(_LB + "labelArc"):
                c, (role, txt) = lc.get(a.get(_XL + "from")), lab.get(a.get(_XL + "to"), ("", ""))
                if c and txt and (role.endswith("/role/label") or c not in out):
                    out[c] = txt
        return out

    def find(self, value: float, tol: float) -> list[dict]:
        """원문 단위(백만원·천원)로 반올림된 값 → 반올림 범위 안의 XBRL 사실 전부."""
        lo = bisect.bisect_left(self._keys, value - tol)
        hi = bisect.bisect_right(self._keys, value + tol)
        return [h for k in self._keys[lo:hi] for h in self.by_val[k]]


def _instance(path) -> Optional[_Instance]:
    key = str(path)
    if key not in _instances:
        try:
            _instances[key] = _Instance(path)
        except Exception:      # noqa: BLE001  깨진 XBRL 하나 때문에 전체를 멈추지 않는다
            _instances[key] = None
    return _instances[key]


def _table_hints(result: dict) -> dict[int, dict]:
    """표 번호 → {scope 후보, period 후보}. 장 제목("3. 연결재무제표 주석")은 블록 순서로 추적한다."""
    tables = {t["table_no"]: t for t in result.get("tables", [])}
    chapter, out = "", {}
    for b in result.get("blocks", []):
        if b.get("kind") == "heading" and _CHAPTER.match((b.get("text") or "").strip()):
            chapter = b["text"].strip()
        if b.get("kind") != "table" or b.get("table_no") not in tables:
            continue
        t = tables[b["table_no"]]
        sec, cap = " > ".join(t.get("section_path") or []), t.get("caption") or ""
        sc = {"consolidated"} if ("연결" in chapter or "(연결)" in sec) else ({"separate"} if chapter else set())
        kinds = {m.group(1) for m in _PERIOD_WORD.finditer(cap)}
        # '당기 및 전기 중 … 변동내역'처럼 둘 다 나오면 좁히지 않는다 (한 표에 당기·전기 열이 함께 있다)
        per = {"current"} if kinds == {"당"} else ({"prior"} if kinds == {"전"} else set())
        out[b["table_no"]] = {"scope": sc, "period": per}
    return out


def _dates(per: str, item: str, end: date) -> set[str]:
    """표 기간(당/전)과 행(기초/기말) → 기대 날짜. 분기 증감표는 연초부터 누계라 기초 = 전년 말."""
    y = end.year if per == "current" else end.year - 1
    if item == "기초":
        return {date(y - 1, 12, 31).isoformat()}
    if item == "기말":
        return {date(y, end.month, end.day).isoformat()}
    return set()


def _n(s: str) -> str:
    return re.sub(r"[\s()\[\]·ㆍ,]|구성요소", "", s or "")


def _cells_and_mult(result: dict) -> tuple[dict, dict]:
    """(표, 행, 열) → 셀, 표 → 단위 배율(백만원 → 1e6, 원래 값과 원 단위 값의 비율)."""
    cells, mult = {}, {}
    for t in result.get("tables", []):
        for c in t["cells"]:
            cells[(t["table_no"], c["r"], c["c"])] = c
            if t["table_no"] not in mult and c.get("value") and c.get("value_base"):
                mult[t["table_no"]] = abs(c["value_base"] / c["value"])
    return cells, mult


def _axis_of(mv: list[dict]):
    """사실 → 자산 종류 축 ("c", 열) 또는 ("r", 행). 기초가 한 행에 모여 있으면 열, 아니면 행(전치 표)."""
    rows_of = defaultdict(set)
    for f in mv:
        if f["item"] == "기초":
            rows_of[f["table_no"]].add(f["r"])
    return lambda f: ("c", f["c"]) if len(rows_of.get(f["table_no"], ())) <= 1 else ("r", f["r"])


def _confirm_with_xbrl(facts: list[dict], result: dict, insts: list, end: date) -> None:
    """증감표 사실의 연결/별도·기간·자산 종류를 XBRL 값으로 확인해 제자리에서 고친다."""
    insts = [i for i in insts if i is not None]
    mv = [f for f in facts if f["kind"] == "movement"]
    if not insts or not mv:
        return
    hints = _table_hints(result)
    cells, mult = _cells_and_mult(result)
    axis = _axis_of(mv)

    hits_of, svote, kvote, big = [], defaultdict(Counter), defaultdict(Counter), defaultdict(float)
    for f in mv:
        tno, v = f["table_no"], f["value"]
        if f["item"] not in ("기초", "기말") or v is None or abs(v) < 1:
            continue
        h0 = hints.get(tno, {})
        m = mult.get(tno, 1.0)
        found = [h for ins in insts for h in ins.find(v, 0.5 * m if m > 1 else 0.5)]
        ok = [(h, per) for per in (h0.get("period") or {"current", "prior"})
              for h in found if h["instant"] in _dates(per, f["item"], end)
              and h["scope"] in (h0.get("scope") or {"consolidated", "separate"})]
        for h, per in ok:
            if abs(v) >= _SMALL_VALUE:
                svote[tno][h["scope"]] += 1
                big[tno] = max(big[tno], abs(v))
            kvote[tno][h["concept"]] += 1
        hits_of.append((tno, axis(f), ok))

    # 우연히 같은 값 하나로 정하지 않는다: 표 전체에서 일치 2건 이상, 또는 10억원 이상 값 일치
    tscope = {t: c.most_common(1)[0][0] for t, c in svote.items()
              if c.most_common(1)[0][1] >= _MIN_SUPPORT or big[t] >= _BIG_VALUE}
    # 같은 값이 다른 주석(리스·부문별 영업권)에도 있으면 표의 주된 개념으로 거른다
    tcons = {t: {k for k, n in c.items() if n >= 0.25 * sum(c.values())} for t, c in kvote.items()}
    pvote = defaultdict(Counter)
    for tno, ax, ok in hits_of:
        for h, per in ok:
            if h["scope"] == tscope.get(tno) and h["concept"] in tcons.get(tno, ()):
                pvote[(tno, ax)][per] += 1
    cper = {k: c.most_common(1)[0][0] for k, c in pvote.items()}      # 기간은 열(축)마다: 한 표에 당기·전기 열
    mvote = defaultdict(Counter)
    for tno, ax, ok in hits_of:
        for h, per in ok:
            if h["scope"] == tscope.get(tno) and h["concept"] in tcons.get(tno, ()) and per == cper.get((tno, ax)):
                mvote[(tno, ax)][(h["members"], h["concept"] if not h["members"] else "")] += 1
    label = {}
    for ins in insts:
        label.update(ins.label)

    def ko(q):
        return re.sub(r"\s*\[(구성요소|member|개요)\]\s*$", "", label.get(q.replace(":", "_"), q.split(":")[-1]), flags=re.I)

    # 표 안에 member 없는 진짜 합계 축이 따로 있으면, 계정명과 같은 이름의 member 축은 소계다
    has_total = {t for (t, _), c in mvote.items() if c.most_common(1)[0][0][0] == ()}
    touched = defaultdict(list)
    for f in mv:
        tno, ax = f["table_no"], axis(f)
        if tno not in tscope:
            continue
        f["scope"], f["label_source"] = tscope[tno], "xbrl"
        if cper.get((tno, ax)):
            f["period"] = cper[(tno, ax)]
        if mvote.get((tno, ax)):
            mem, _ = mvote[(tno, ax)].most_common(1)[0][0]
            member, note = _member_label(f, mem, cells, ko, ax[0], tno in has_total)
            f["member"] = member
            if note:
                f["note"] = f"{f['note']}; {note}" if f["note"] else note
        touched[(tno, ax)].append(f)
    _recheck(touched)


def _member_label(f: dict, mem: tuple, cells: dict, ko, ax: str, table_has_total: bool) -> tuple[str, str]:
    """XBRL member → 표기. 합계면 '', 같은 뜻이면 원래 표기 유지, 아니면 머리글 낱말 중 맞는 것."""
    if mem == ():
        return "", ""
    spec = [m for _, m in mem if m not in _GENERIC] or [m for _, m in mem]
    xko = ko(spec[-1])
    if _n(re.sub(r"(의)?\s*(합계|총계)$", "", xko)) == _n(f["account"]):
        # 계정명과 같은 member: 따로 합계 축이 있으면 소계, 없으면 그 표의 합계
        return (f"{f['account']}(소계)" if table_has_total else ""), ""
    old = f["member"] or ""
    if old and (_n(old) in _n(xko) or _n(xko) in _n(old)):
        return old, ""
    c = cells.get((f["table_no"], f["r"], f["c"]))
    heads = (c["col_path"] if ax == "c" else c["row_path"]) if c else []      # 자산 종류 축의 머리글만
    for h in reversed(heads):
        if re.search(r"합\s*계|총\s*계", h):
            continue
        if _n(h) and (_n(h) == _n(xko) or _n(xko) in _n(h)):
            return re.sub(r"\s+", "", h), ""
    if not old:      # 합계로 봤지만 XBRL은 특정 자산 종류
        return xko, ""
    return old, f"XBRL 자산 종류: {xko}"


def _recheck(touched: dict) -> None:
    """이름표를 바꾼 축마다 기초 + Σ증감 = 기말을 다시 검산 (축 안의 위치 순서로 기초~기말 사이만)."""
    for (tno, ax), fs in touched.items():
        pos = (lambda f: f["r"]) if ax[0] == "c" else (lambda f: f["c"])
        fs = sorted(fs, key=pos)
        o = next((f for f in fs if f["item"] == "기초"), None)
        c = next((f for f in fs if f["item"] == "기말" and (o is None or pos(f) > pos(o))), None)
        if not o or not c or o["value"] is None or c["value"] is None:
            continue
        mid = [f for f in fs if pos(o) < pos(f) < pos(c) and f["status"] != "breakdown" and f["value"] is not None
               and not is_total_label((f["label"] or "").split(" > ")[-1])]
        s = o["value"] + sum(f["value"] for f in mid)
        ok = abs(s - c["value"]) <= max(2.0 * 1e6, abs(c["value"]) * 0.001)
        for f in fs:
            new = "verified" if ok else "mismatch"
            if f["status"] in ("verified", "mismatch", "unchecked") and new != f["status"]:
                f["status"] = new
                f["note"] = ("XBRL 이름표로 다시 검산: 일치" if ok
                             else f"XBRL 이름표로 다시 검산: 기초+증감={s:,.0f} vs 기말={c['value']:,.0f}")


# ─── XBRL로 확인되지 않은 표: 문서 안 단서와 숫자 관계 ──────────────────────

_TOTAL_WORD = re.compile(r"합\s*계|총\s*계|^계$")


def _label_from_document(facts: list[dict], result: dict) -> None:
    """XBRL로 확인되지 않은 증감표 사실(XBRL을 안 넘긴 경우 전부)에 문서 안 단서로 이름표를 붙인다.
    1) 연결/별도: 표가 속한 장 제목("3. 연결재무제표 주석" / "5. 재무제표 주석")·섹션의 "(연결)"
       기간: 표 위 문구가 "당분기"·"전기" 중 한쪽만일 때
    2) 자산 종류: 앞 열들의 기초·기말 합이 이 열과 같으면 이 열은 소계/합계 (머리글 이름과 무관하게 숫자로)
    단서로 정한 사실은 label_source="document"."""
    mv = [f for f in facts if f["kind"] == "movement" and f["label_source"] != "xbrl"]
    if not mv:
        return
    hints = _table_hints(result)
    cells, mult = _cells_and_mult(result)
    axis = _axis_of(mv)
    touched = defaultdict(list)
    for f in mv:
        h = hints.get(f["table_no"], {})
        if len(h.get("scope", ())) == 1:
            f["scope"], f["label_source"] = next(iter(h["scope"])), "document"
        if len(h.get("period", ())) == 1:
            f["period"], f["label_source"] = next(iter(h["period"])), "document"
    by_t = defaultdict(list)
    for f in mv:
        by_t[f["table_no"]].append(f)
    for tno, fs in by_t.items():
        roles = _sum_roles(fs, axis, max(2.0 * mult.get(tno, 1.0), 1.0))
        for f in fs:
            role = roles.get(axis(f))
            c = cells.get((tno, f["r"], f["c"]))
            heads = (c["col_path"] if axis(f)[0] == "c" else c["row_path"]) if c else []
            name = next((re.sub(r"\s+", "", h) for h in reversed(heads) if _n(h) and not _TOTAL_WORD.search(h)), "")
            if role == "total":
                member = ""
            elif role == "sub":
                member = f"{f['account']}(소계)" if (not name or _n(name) == _n(f["account"])) else name
            elif f["member"] == "" and name and _n(name) != _n(f["account"]) and roles:
                member = name        # 합계로 봤지만 숫자로는 합계가 아님 → 머리글의 구체 낱말
            else:
                member = f["member"]
            if member != f["member"]:
                f["member"], f["label_source"] = member, "document"
        for f in fs:
            if f["label_source"] == "document":
                touched[(tno, axis(f))].append(f)
    # 이름표를 바꾼 축은 그 축의 사실 전부로 다시 검산
    full = defaultdict(list)
    for f in mv:
        if (f["table_no"], axis(f)) in touched:
            full[(f["table_no"], axis(f))].append(f)
    _recheck(full)


def _sum_roles(fs: list[dict], axis, tol: float) -> dict:
    """자산 종류 축마다 역할: 앞 축들의 기초·기말 합과 같으면 "sub"(소계) 또는 "total"(합계)."""
    vals = defaultdict(dict)
    for f in fs:
        if f["item"] in ("기초", "기말") and f["value"] is not None:
            vals[axis(f)].setdefault(f["item"], f["value"])
    order = sorted(vals, key=lambda a: a[1])
    if len(order) < 3:
        return {}

    def adds_up(parts, k):
        for it in ("기초", "기말"):
            if it not in vals[k] or any(it not in vals[p] for p in parts):
                return False
            if abs(sum(vals[p][it] for p in parts) - vals[k][it]) > max(tol * len(parts), abs(vals[k][it]) * 1e-6):
                return False
        return abs(vals[k]["기초"]) + abs(vals[k]["기말"]) > 0

    roles, run, subs = {}, [], []
    for j, k in enumerate(order):
        if subs and adds_up(subs + run, k):
            roles[k], run, subs = "total", [], []
        elif len(run) >= 2 and adds_up(run, k):
            roles[k] = "total" if (j == len(order) - 1 and not subs) else "sub"
            if roles[k] == "sub":
                subs.append(k)
            run = []
        else:
            run.append(k)
    return roles
