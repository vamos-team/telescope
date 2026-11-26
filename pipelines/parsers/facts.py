"""사실 추출 — parse() 결과의 표에서 장부금액 증감표·잔액표를 찾아 "사실" 한 줄씩으로 정리하고 검산한다.

    from parsers import parse
    from parsers.facts import extract, report_period
    res = parse("LGU_3분기보고서.xml")
    report_period(res)       # → {"fiscal_year": 2026, "report_code": "11014", "period_end": "2026-09-30"}
    extract(res)             # → [{"kind": "movement", "account": "유형자산",
                             #     "account_concept": "ifrs-full_PropertyPlantAndEquipment", "member": "토지", "item": "기초",
                             #     "item_concept": "",
                             #     "period": "current", "value": 919615000000, "value_eok": 9196.15,
                             #     "status": "verified", ...}, ...]

회사·연도별 형식 분기 없이 한 규칙으로 처리한다.
1. 표 고르기: 항목 축에 기초·기말이 모두 있는 표만 증감표로 본다. 기초·기말 글자는 XBRL 기초·기말 이름표에서
   한쪽에만 많이 나오는 두 글자('기초'·'기말')를 뽑아 쓴다. 기초 + 증감 = 기말이 한 번도 맞지 않는 표는 버린다.
2. 축 판별: 행과 열 중 기초와 기말 사이에 줄이 더 많은 쪽이 항목 축.
   SK텔레콤(항목=행)과 SK브로드밴드(항목=열) 같은 방향 차이를 코드 분기 없이 흡수한다.
3. 계정: 캡션 → 가까운 제목 → 문맥 순으로 XBRL 증감표 계정 이름표(xbrl_vocab.json)가 들어 있는 글의 계정.
   어느 글에도 없으면 표 안 줄·열 이름을 계정별 XBRL 항목·자산 종류 이름표와 임베딩으로 맞춰 고른다.
   account = 이름표, account_concept = XBRL 개념.
   증감 항목(기초·기말 사이 줄): 그 계정의 XBRL 증감 항목 이름표(xbrl_vocab.json) 중 가장 가까운 것을
   임베딩으로 고른다(align). 한 표의 두 줄에 같은 항목을 주지 않는다(점수 높은 짝부터).
   item = 그 이름표, item_concept = XBRL 개념. 감소 여부도 XBRL에서(negatedLabel).
   임베딩 모델은 .env의 PARSER_EMBED_MODEL.
4. 자산 종류(member): 반대 축 경로의 가장 깊은 자산 이름을 그 계정의 XBRL 자산 종류 이름표와 임베딩으로 맞춘다
   (한 표의 서로 다른 이름에 같은 구성요소를 주지 않는다, member_concept = XBRL 개념). 합계 라벨이면 ''.
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
9. 같은 공시 XBRL이 있으면 XBRL 사실(extract_xbrl: 표시 구조로 바로 꺼낸 증감·잔액)을 먼저 쓰고, 표 사실은 XBRL이 덮지 못한
   자산·기간 줄만 남긴다(_merge_xbrl). 회사가 XBRL에 태깅하지 않은 증감 행은 같은 표 줄에서 채운다(_fill_untagged).
"""
from __future__ import annotations

import bisect
import itertools
import re
from collections import Counter, defaultdict
from datetime import date, timedelta
from types import SimpleNamespace
from typing import Optional

import numpy as np

from . import xbrl_vocab
from .align import align, similarity
from .numbers import detect_period, is_money_unit, periods_in, unspace
from .tables import is_total_label
from .xbrl_reader import _XbrlDoc, _local, _xbrl_period_end, _xdoc


def _c(d: dict) -> list[tuple[str, list[re.Pattern]]]:
    return [(k, [re.compile(p, re.I) for p in v]) for k, v in d.items()]


# ─── 사전 (위에서부터 먼저 맞는 것이 채택된다) ───────────────────────────

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
_MEASURE_WORDS = re.compile(r"^(순\s*)?(장부\s*)?금액(\s*합계)?$|^(당기|전기)$|단위\s*[:：]|^공시\s*금액$")
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


def _open_close_words() -> dict[str, str]:
    """XBRL 기초·기말 이름표('기초 유형자산', '기말 유형자산' …)에서 한쪽에만 많이 나오는 두 글자 → {글자: '기초'|'기말'}.
    회사 공시의 '당기초'·'전기말'·'분기초'도 이 글자를 담는다."""
    key = id(xbrl_vocab.load())
    if _OC.get("key") != key:
        accs = list(xbrl_vocab.load()["accounts"].values())
        df = {}
        for role in ("start", "end"):
            c = Counter()
            for e in accs:
                t = re.sub(r"\s+", "", e[role])
                c.update({t[i:i + 2] for i in range(len(t) - 1)})
            df[role] = c
        words = {}
        for role, other, name in (("start", "end", "기초"), ("end", "start", "기말")):
            words[max(df[role], key=lambda g: df[role][g] - df[other][g])] = name
        _OC.update(key=key, words=words)
    return _OC["words"]


_OC: dict = {}


def _match_item(parts: list[str]) -> Optional[str]:
    """가장 구체적인(마지막) 라벨부터 기초·기말 글자를 찾는다. 둘 다 있으면 기초."""
    words = _open_close_words()
    for p in reversed(parts):
        t = re.sub(r"\s+", "", _norm(p))
        hit = [name for w, name in words.items() if w in t]
        if hit:
            return "기초" if "기초" in hit else "기말"
    return None


def _member_text(parts: list[str], account: tuple[str, str]) -> tuple[Optional[str], str]:
    """반대 축 경로 → (자산 이름 글, 방식). 가장 깊은 의미 있는 라벨이 정한다. 그 표의 계정 이름 머리글('유형자산')만
    건너뛴다(자본 표의 '자본금'·'이익잉여금'은 자산 종류). ['유형자산','토지','장부금액 합계'] → '토지', ['유형자산 합계'] → None(total), 없으면 None(implicit_total)."""
    meaningful = [p for p in parts
                  if _norm(p) and not any(m.search(_norm(p)) for m in MEASURE_DROP)
                  and not _MEASURE_WORDS.search(_norm(p))
                  and not (len(_norm(p)) <= 8 and detect_period(_norm(p)))]     # '당기말'·'2024년' 같은 기간 머리글
    for p in reversed(meaningful):
        n = _norm(p)
        if re.sub(r"\s+", "", n) == re.sub(r"\s+", "", _norm(account[1])):
            continue
        if is_total_label(n):
            return None, "total"
        return n, "name"
    return None, "implicit_total"


def _member_names(account: tuple[str, str], texts) -> dict[str, tuple[str, str]]:
    """표 안 자산 이름 글들 → (XBRL 구성요소 한글 이름표, 개념). 후보는 그 계정과 가까운 계정의 자산 종류 이름표
    (드문 것 제외). 한 표의 서로 다른 글에 같은 구성요소를 주지 않는다. 남는 글과, 짝과 두 글자 조각이 하나도
    겹치지 않는 글('전기통신설비' ↔ '기계장치', 회사 고유 자산 종류)은 원문 그대로(개념 '')."""
    accs = xbrl_vocab.load()["accounts"]
    slots, seen = [], set()
    for b in _vocab_accounts(account):
        e = accs[b]
        for c, (lab, k) in e["members"].items():
            if c not in seen and k >= _RARE * e["n"]:
                seen.add(c)
                slots.append({"id": c, "path": [re.sub(r"\s*\[구성요소\]\s*$", "", lab)], "vtype": "number"})
    texts = list(dict.fromkeys(x for x in texts if x))
    out = {}
    for x, p in zip(texts, align([{"path": [x], "vtype": "number"} for x in texts], slots, one_to_one=True)):
        lab = slots[p["index"]]["path"][0] if p["slot"] else ""
        out[x] = (lab, p["slot"]) if lab and _bigrams(x) & _bigrams(lab) else (x, "")
    return out


def _bigrams(t: str) -> set[str]:
    t = re.sub(r"\s+", "", t.lower())
    return {t[i:i + 2] for i in range(len(t) - 1)}


def _table_member_names(account: tuple[str, str], path_lists) -> dict[str, tuple[str, str]]:
    return _member_names(account, [_member_text(ps, account)[0] for ps in path_lists])


def _member_of(parts: list[str], names: dict, account: tuple[str, str]) -> tuple[tuple[str, str], str]:
    """반대 축 경로 → ((member, member_concept), method). member '' = 집계."""
    x, how = _member_text(parts, account)
    if x is None:
        return ("", ""), how
    lab, con = names.get(x, (x, ""))
    return (lab, con), ("xbrl" if con else "raw")


def _account_slots() -> list[dict]:
    return [{"id": b, "path": [e["label"]], "vtype": "number"} for b, e in xbrl_vocab.load()["accounts"].items()]


def _account_of(t) -> Optional[tuple[str, str]]:
    """표의 계정 → (XBRL 계정 개념, 한글 이름표). 캡션 → 소속 제목(가까운 것부터) → 각주가 아닌 문맥 순으로,
    XBRL 계정 이름표가 들어 있는 첫 글의 계정. 한 글에 이름표가 여럿이면 다른 이름표의 일부가 아닌 것('자산'
    말고 '유형자산') 중 먼저 나오는 것. 각주는 다른 계정을 언급하는 경우가 많아 뺀다.
    어느 글에도 없으면('14. 리스') 표 안의 줄·열 이름으로 고른다(_account_from_cells)."""
    ctx = [x for x in t.context if not re.match(r"^\s*[(（]\s*(주|\*)", x or "")]
    texts = list(dict.fromkeys(n for x in [t.caption] + list(reversed(t.section_path)) + ctx if (n := _norm(x or ""))))
    accs = xbrl_vocab.load()["accounts"]
    by_label = {}
    for b, e in sorted(accs.items(), key=lambda x: x[1]["n"]):        # 같은 이름표면 많이 쓰인 계정
        by_label[re.sub(r"\s+", "", e["label"])] = b
    named = []
    for x in texts:
        k = re.sub(r"\s+", "", x)
        found = [(k.find(lab), lab) for lab in by_label if lab and lab in k]
        found = sorted((i, lab) for i, lab in found if not any(lab != m and lab in m for _, m in found))
        if found:
            named.append(found)
    # 계정 하나만 가리키는 글을 먼저('유형자산, 투자부동산, 무형자산' 같은 묶음 제목은 뒤로)
    for found in sorted(named, key=lambda f: len(f) > 1):
        b = by_label[found[0][1]]
        return b, accs[b]["label"]
    b = _account_from_cells(t.cells)
    return (b, accs[b]["label"]) if b else None


def _account_from_cells(cells) -> Optional[str]:
    """표 안의 줄·열 이름 ↔ 계정별 XBRL 증감 항목·자산 종류·측정 이름표. 줄 이름마다 그 계정 이름표 중 가장
    가까운 것의 점수를 평균해 가장 높은 계정. 사용권자산(건물·차량운반구)과 리스부채(이자비용·리스료 지급)처럼
    제목이 같아도 줄 이름이 다른 표를 가른다."""
    labels = list(dict.fromkeys(n for c in cells for p in c.row_path[-1:] + c.col_path[-1:]
                                if (n := _norm(p)) and re.search(r"[가-힣A-Za-z]", n)))
    accs = xbrl_vocab.load()["accounts"]
    owner, slots = [], []
    for b, e in accs.items():
        for c, v in {**e["items"], **e["members"], **e["measures"]}.items():
            owner.append(b)
            slots.append({"path": [v[0]], "vtype": "number"})
    if not labels or not slots:
        return None
    sim = similarity([{"path": [x], "vtype": "number"} for x in labels], slots)
    owner = np.array(owner)
    score = {b: sim[:, owner == b].max(axis=1).mean() for b in accs if (owner == b).any()}
    return max(score, key=score.get)


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


def _member_two(other_parts: list[str], measure_parts: list[str], names: dict, account) -> tuple[tuple[str, str], str]:
    """잔액표의 자산 종류: 측정 구분의 반대 축을 먼저, 없으면 측정 축 머리글에서."""
    m1, k1 = _member_of(other_parts, names, account)
    m2, k2 = _member_of(measure_parts, names, account)
    for m, k, want in ((m1, k1, "xbrl"), (m2, k2, "xbrl"), (m1, k1, "raw"), (m2, k2, "raw")):
        if k == want:
            return m, k
    return ("", ""), "total"


# ─── 추출 ─────────────────────────────────────────────────────

def _fact(t, kind, period, account, member, item, c, label, method, status, note, concept="") -> dict:
    """account = (XBRL 계정 개념, 한글 이름표), member = (한글 이름표, XBRL 구성요소 개념)."""
    v = c.value_base
    if v is None and status != "breakdown":
        status = "unit_missing"
        note = (note + ";" if note else "") + "unit_missing"
    return {"kind": kind, "scope": "", "label_source": "", "period": period, "account": account[1], "account_concept": account[0],
            "member": member[0], "member_concept": member[1],
            "item": item, "item_concept": concept,
            "value": v, "value_eok": v / 1e8 if (v is not None and is_money_unit(c.unit)) else None,
            "unit": c.unit, "status": status, "note": note, "method": method, "label": _norm(label),
            "table_no": t.table_no, "r": c.r, "c": c.c, "src_ref": c.src_ref, "page": t.page, "sheet": t.sheet,
            "section": " > ".join(t.section_path)}


def _balance_groups(groups: dict, t, account: tuple[str, str], method: str) -> list[dict]:
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
                             "verified" if ok else "mismatch", "" if ok else "components_mismatch"))
    return out


def _balance_openclose(t, fy, account: tuple[str, str], hits: dict, item_axis: str) -> list[dict]:
    """증감 항목 없이 '기초 | 기말' 두 칸만 있는 표: (자산, 측정) × (기초, 기말)."""
    groups: dict[tuple, dict] = defaultdict(dict)
    names = _table_member_names(account, [c.col_path if item_axis == "row" else c.row_path for c in t.cells])
    for c in t.cells:
        idx, other = (c.r, c.col_path) if item_axis == "row" else (c.c, c.row_path)
        item = hits.get(idx)
        if item not in ("기초", "기말"):
            continue
        ms = _measure_of(other)
        if not ms:
            continue
        member, mmethod = _member_of(other, names, account)
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
    account = _account_of(t)
    if not account:
        return []
    groups: dict[tuple, dict] = defaultdict(dict)
    names = _table_member_names(account, [p for c in t.cells for p in (c.row_path, c.col_path)])
    for c in t.cells:
        ms = col_ms[c.c] if axis == "col" else row_ms[c.r]
        if not ms:
            continue
        other, mpath = (c.row_path, c.col_path) if axis == "col" else (c.col_path, c.row_path)
        member, mmethod = _member_two(other, mpath, names, account)
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
          XBRL 값으로 확인해 다시 붙이고(label_source="xbrl"), 회사가 XBRL에 쓴 이름표를 표 증감 항목의 후보 이름으로
          쓴다. 없으면 scope(연결/별도)는 "".
    """
    period = report_period(result)
    fy = fiscal_year or period.get("fiscal_year")
    company = _company_names(xbrl or [])
    facts: list[dict] = []
    for td in result.get("tables", []):
        if not td.get("cells"):
            continue
        t = SimpleNamespace(**{k: v for k, v in td.items() if k != "cells"})
        t.cells = [SimpleNamespace(**c) for c in td["cells"]]
        facts.extend(_extract_table(t, fy, company))
    if xbrl and period.get("period_end"):
        _confirm_with_xbrl(facts, result, [_instance(p) for p in xbrl], date.fromisoformat(period["period_end"]))
    _label_from_document(facts, result)
    for f in facts:
        f.pop("_pair", None)
    if xbrl and period.get("period_end"):
        facts = _merge_xbrl(facts, result, xbrl, period["period_end"])
    return facts


def _company_names(xbrl: list) -> dict[str, tuple[list[str], int]]:
    """넘겨받은 XBRL zip들(같은 공시·이전 정기보고서)에서 개념 → (회사가 쓴 한글 이름표들(설명문 제외), 사실 수)."""
    names: dict[str, list[str]] = defaultdict(list)
    used: Counter = Counter()
    for p in xbrl:
        try:
            d = _xdoc(str(p))
        except Exception:      # noqa: BLE001
            continue
        for c in d.labels:
            for t in d.names(c):
                if t not in names[c]:
                    names[c].append(t)
        for c, fs in d.facts.items():
            used[c] += len(fs)
    return {c: (names[c], used[c]) for c in names}


def _merge_xbrl(facts: list[dict], result: dict, xbrl: list, period_end: str) -> list[dict]:
    """같은 공시 XBRL의 사실(extract_xbrl)을 먼저 쓰고, 표 사실은 XBRL이 덮지 못한 표만 남긴다.
    같은 공시 = XBRL 보고 기간 말이 문서 표지의 기간 말과 같은 zip(이전 정기보고서 zip은 이름표 확인에만 쓴다).
    표 안의 한 자산·기간 줄마다, 증감이면 기초·기말이 한 XBRL 증감 묶음의 기초·기말과 같을 때, 잔액이면 구성요소
    값이 전부 XBRL 잔액 사실에 있을 때 XBRL이 덮은 것으로 보고 뺀다. 아니면 남긴다(XBRL에 주석이 없거나 회사가
    태깅하지 않은 줄)."""
    xs = []
    for p in xbrl:
        try:
            if _xbrl_period_end(p) == period_end:
                xs.extend(extract_xbrl(p))
        except Exception:      # noqa: BLE001  깨진 XBRL 하나 때문에 전체를 멈추지 않는다
            continue
    if not xs:
        return facts
    keys = sorted(abs(f["value"]) for f in xs if f["value"] is not None and f["kind"] == "balance")
    # 증감 묶음은 기초·기말을 한 벌로 본다: 값이 각각 다른 묶음에 있는 것(당기 묶음의 기초 = 전기 표의 기말)은 덮은 게 아니다
    pairs = defaultdict(dict)
    for f in xs:
        if f["kind"] == "movement" and f["item"] in ("기초", "기말") and f["value"] is not None:
            pairs[(f["section"], f["account_concept"], f["member_concept"], f["scope"], f["period"])][f["item"]] = abs(f["value"])
    pairs = [(p["기초"], p["기말"]) for p in pairs.values() if len(p) == 2]
    _, mult = _cells_and_mult(result)

    def tol_of(tno) -> float:
        m = mult.get(tno, 1.0)
        return 0.5 * m if m > 1 else 0.5

    def in_xbrl(v: float, tno) -> bool:
        tol = tol_of(tno)
        i = bisect.bisect_left(keys, abs(v) - tol)
        return i < len(keys) and keys[i] <= abs(v) + tol

    def pair_in_xbrl(o: float, c: float, tno) -> bool:
        tol = tol_of(tno)
        return any(abs(abs(o) - a) <= tol and abs(abs(c) - b) <= tol for a, b in pairs)

    # 표 안의 한 자산·기간 줄(증감: 기초~기말 한 벌, 잔액: 한 시점의 구성요소 한 벌)마다 판단한다
    by_line = defaultdict(list)
    for f in facts:
        by_line[(f["table_no"], f["kind"], f["member"], f["period"])].append(f)
    keep, covered = [], []
    for (tno, *_), fs in by_line.items():
        if fs[0]["kind"] == "movement":
            o = next((f["value"] for f in fs if f["item"] == "기초" and f["value"] is not None), None)
            c = next((f["value"] for f in fs if f["item"] == "기말" and f["value"] is not None), None)
            hit = o is not None and c is not None and (o or c) and pair_in_xbrl(o, c, tno)
        else:
            anchors = [f for f in fs if f["value"] is not None and f["value"] != 0]
            hit = bool(anchors) and all(in_xbrl(f["value"], tno) for f in anchors)
        if hit:
            covered.append(fs)
            continue
        keep.extend(fs)
    return xs + _fill_untagged(xs, covered, mult) + keep


def _fill_untagged(xs: list[dict], covered: list[list[dict]], mult: dict) -> list[dict]:
    """XBRL 증감 묶음의 검산이 안 맞을 때(회사가 일부 항목을 태깅하지 않음), 같은 기초·기말 값의 표 줄에서
    XBRL에 없는 값의 증감 행을 찾아 그 합이 모자란 금액과 같으면 채운다. 개념은 표 파서가 붙인 것(이름표 임베딩).
    채운 뒤 묶음 전체를 verified로 바꾼다. 합이 안 맞으면 채우지 않는다."""
    groups = defaultdict(list)
    for f in xs:
        if f["kind"] == "movement":
            groups[(f["section"], f["account_concept"], f["member_concept"], f["scope"], f["period"])].append(f)
    out = []
    for fs in covered:
        mv = [f for f in fs if f["kind"] == "movement"]
        o = next((f for f in mv if f["item"] == "기초"), None)
        c = next((f for f in mv if f["item"] == "기말"), None)
        if not o or not c or o["value"] is None or c["value"] is None:
            continue
        tno = o["table_no"]
        m = mult.get(tno, 1.0)
        tol = 0.5 * m if m > 1 else 0.5
        same = lambda a, b: a is not None and b is not None and abs(abs(a) - abs(b)) <= tol
        for g in groups.values():
            go = next((f for f in g if f["item"] == "기초"), None)
            gc = next((f for f in g if f["item"] == "기말"), None)
            if not go or not gc or go["status"] != "mismatch" or not same(go["value"], o["value"]) \
                    or not same(gc["value"], c["value"]):
                continue
            top = [f for f in g if f["item"] not in ("기초", "기말") and f["status"] != "breakdown"]
            gap = gc["value"] - go["value"] - sum(f["value"] for f in top)
            have = [f["value"] for f in g if f["value"] and f["item"] not in ("기초", "기말")]    # 증감 항목 값하고만 비교
            miss = [f for f in mv if f["item"] not in ("기초", "기말") and f["status"] != "breakdown"
                    and f["value"] and not any(same(f["value"], h) for h in have)]
            if not miss or abs(sum(f["value"] for f in miss) - gap) > max(2.0 * m, abs(gap) * 0.001):
                continue
            for f in miss:
                out.append({**f, "scope": go["scope"], "period": go["period"], "account": go["account"],
                            "account_concept": go["account_concept"], "member": go["member"],
                            "member_concept": go["member_concept"], "section": go["section"], "status": "verified",
                            "label_source": "xbrl+document",
                            "note": "filled_from_table"})
            for f in g:
                if f["status"] == "mismatch":
                    f["status"], f["note"] = "verified", "filled_from_table"
            break
    return out


def _close(a: float, b: float) -> bool:
    return abs(a - b) <= max(2.0, abs(b) * 0.001)


def _check(open_v, close_v, mid: list[int], v: dict, lab: dict, neg: set, unit) -> tuple:
    """한 자산·기간의 한 짝 검산 → (상태, 메모, 내역 행 집합, 부호를 바꾼 줄 집합)."""
    # 항목 라벨 자체가 소계('증감 계')인 것은 뺀다. 자산이 합계인 열은 빼면 안 된다
    mid = [i for i in mid if not is_total_label(lab[i].split(" > ")[-1])]
    s = open_v + sum(v[i] for i in mid)
    if _close(s, close_v):
        return "verified", "", set(), set()
    # 행 하나를 빼면 정확히 맞는가 → 그 행은 위 항목의 내역 행(이중 계산)
    exact = max(2.0, float(len(mid)))
    hit = [i for i in mid if v[i] != 0 and abs((s - v[i]) - close_v) <= exact]
    # 감소 항목을 양수로 적는 표: 양수인 감소 항목을 빼면 맞는가
    dec = [i for i in mid if i in neg and v[i] > 0]
    if len(hit) == 1:
        return "verified", "", {hit[0]}, set()
    if dec and abs(s - 2 * sum(v[i] for i in dec) - close_v) <= exact:
        return "verified", "sign_flipped", set(), set(dec)
    return "mismatch", "rollforward_mismatch", set(), set()


def _subtotals(order: list[int], bounds: list[int], val: dict) -> set[int]:
    """소계 줄: 바로 위나 아래의 연속 두 줄 이상의 합과 모든 자산·기간에서 같은 줄(0이 아닌 값이 두 곳 이상).
    자본변동표의 '총포괄손익' = 당기순이익 + 기타포괄손익 같은 줄. 기초·기말 줄을 넘지 않는다."""
    segs, cur = [], []
    for i in order:
        if cur and any(cur[-1] < b < i for b in bounds):
            segs.append(cur)
            cur = []
        cur.append(i)
    if cur:
        segs.append(cur)
    out = set()
    for seg in segs:
        for p, i in enumerate(seg):
            blocks = [seg[p - n:p] for n in range(2, p + 1)] + [seg[p + 1:p + 1 + n] for n in range(2, len(seg) - p)]
            have = [v for v in val.values() if i in v]
            if sum(1 for v in have if v[i]) < 2:
                continue
            for blk in blocks:
                if blk and all(_close(sum(v.get(j, 0) for j in blk), v[i]) for v in have):
                    out.add(i)
                    break
    return out


def _sub_label(parts: list[str]) -> tuple[str, ...]:
    """기초·기말 머리글에서 기초·기말 글자가 든 부분 뒤의 하위 머리글('기초잔액 > 수량' → ('수량',))."""
    words = _open_close_words()
    for k in range(len(parts) - 1, -1, -1):
        if any(w in re.sub(r"\s+", "", _norm(parts[k])) for w in words):
            # 글자가 없는 하위 칸('4,554,244,435', '-')은 머리글이 아니라 값이 머리글 자리에 들어간 것
            return tuple(_norm(p) for p in parts[k + 1:] if re.search(r"[가-힣A-Za-z]", _norm(p)))
    return ()


def _pairs(hits: dict, first: dict, order: list[int], val: dict, subtotal: set, lab: dict, neg: set) -> list:
    """표의 (기초 줄, 기말 줄, 증감 줄들) 짝.
    - 보통: 기초마다 다음 기말과 짝, 그 사이 줄이 증감(당기·전기 묶음이 위아래로 붙은 표는 묶음마다).
    - 기초·기말 아래 하위 머리글이 여럿이면('기초잔액 > 수량 | 지분율 | 장부가액'): 하위 머리글이 같은 기초·기말끼리
      짝짓고, 사이의 증감 줄은 하위 머리글이 같은 짝에 넣거나 검산이 가장 많이 맞는 짝에 넣는다."""
    opens = sorted(i for i, k in hits.items() if k == "기초")
    closes = sorted(i for i, k in hits.items() if k == "기말")
    subs = {i: _sub_label(first.get(i, [])) for i in opens + closes}
    if len({subs[i] for i in opens}) <= 1:
        out = []
        for o in opens:
            e = next((c for c in closes if c > o), None)
            if e is not None and not any(o < x < e for x in opens):
                out.append((o, e, [i for i in order if o < i < e]))
        return out
    by_sub = {}
    for o in opens:
        e = next((c for c in closes if c > o and subs[c] == subs[o]), None)
        if e is not None:
            by_sub.setdefault(subs[o], (o, e))
    keys = list(by_sub)
    lo, hi = min(o for o, _ in by_sub.values()), min(e for _, e in by_sub.values())
    mids = [i for i in order if lo < i < hi]
    fixed = {i: next((k for k in keys if k and k[-1] == _norm(lab[i].split(" > ")[-1])), None) for i in mids}
    free = [i for i in mids if fixed[i] is None][:5]

    def score(assign):
        n = 0
        for k, (o, e) in by_sub.items():
            ms = [i for i in mids if assign.get(i, fixed[i]) == k and i not in subtotal]
            if not ms:
                continue
            for v in val.values():
                if o in v and e in v:
                    n += _check(v[o], v[e], [i for i in ms if i in v], v, lab, neg, "")[0] == "verified"
        return n

    best, best_n = {}, -1
    for combo in itertools.product(keys + [None], repeat=len(free)):
        assign = dict(zip(free, combo))
        n = score(assign)
        if n > best_n:
            best, best_n = assign, n
    return [(o, e, [i for i in mids if best.get(i, fixed[i]) == k]) for k, (o, e) in by_sub.items()]


def _between(hits: dict, idx: set) -> list[int]:
    """항목 축에서 첫 기초와 그 뒤 첫 기말 사이의 줄 번호."""
    o = min((i for i, k in hits.items() if k == "기초"), default=None)
    c = min((i for i, k in hits.items() if k == "기말" and (o is None or i > o)), default=None)
    return [] if o is None or c is None else sorted(i for i in idx if o < i < c and i not in hits)


# 계정의 증감 항목 후보에서, 그 계정 공시 중 이 비율 미만에서만 쓰인 드문 개념은 뺀다.
# 튜닝에 안 쓴 회사로 나눠 세 번 골라 세 번 모두 5% (안 빼면 '취득'이 드문 '영업권의 증가'로 감)
_RARE = 0.05


def _vocab_accounts(account: tuple[str, str]) -> list[str]:
    """표의 계정 + 이름표가 가장 가까운 다른 XBRL 계정. 두 계정의 항목을 함께 후보로 쓴다 — 표의 계정이 후보
    목록에 없는 경우(통신사만 쓰는 '유형자산(사용권자산 포함)')에도 가까운 계정들의 항목으로 고를 수 있게."""
    if account not in _ACC_CACHE:
        second = align([{"path": [account[1]], "vtype": "number"}],
                       [s for s in _account_slots() if s["id"] != account[0]])[0]["slot"]
        _ACC_CACHE[account] = [b for b in (account[0], second) if b]
    return _ACC_CACHE[account]


_ACC_CACHE: dict[tuple, list[str]] = {}


def _name_items(account: tuple[str, str], paths: list[list[str]], company: Optional[dict] = None) -> list[tuple[str, str, bool]]:
    """증감 항목 줄의 라벨 경로들 → [(XBRL 개념, 한글 이름표, 감소 여부)].
    company: 그 회사 XBRL에서 개념마다 쓴 이름표들(예: 사업결합을 통한 취득 → '연결범위변동'). 있으면 같은 개념의
    후보 이름으로 함께 맞춘다. 한 표 안 서로 다른 줄은 서로 다른 개념."""
    accs = xbrl_vocab.load()["accounts"]
    slots, seen = [], set()
    for rank, b in enumerate(_vocab_accounts(account)):
        e = accs[b]
        for c, (label, n, neg) in e["items"].items():
            if c not in seen and n >= _RARE * e["n"]:
                seen.add(c)
                slots.append({"id": c, "path": [label], "vtype": "number", "neg": neg * 2 >= n, "rank": rank})
    items = [{"path": [_norm(p) for p in ps if _norm(p)] or ["-"], "name": _norm(ps[-1]) if ps else "-", "vtype": "number"}
             for ps in paths]
    if not items or not slots:
        return [("", "", False)] * len(items)
    names = [(i, s["path"][0]) for i, s in enumerate(slots)]
    for i, s in enumerate(slots):
        if s["rank"]:          # 회사 이름표는 표 계정의 개념에만(다른 계정 개념에 붙은 같은 이름 '취득'은 약한 증거)
            continue
        for t in (company or {}).get(s["id"], ([], 0))[0]:
            if t != s["path"][0]:
                names.append((i, t))
    sim = similarity(items, [{"path": [t], "vtype": "number"} for _, t in names])
    score = np.full((len(items), len(slots)), -np.inf)
    for j, (i, _) in enumerate(names):
        score[:, i] = np.maximum(score[:, i], sim[:, j])
    # 한 표 안 서로 다른 줄은 서로 다른 항목: '상각'과 '처분'이 모두 '처분과 폐기'로 가는 것을 막는다(점수 높은 짝부터).
    # 점수가 같으면(회사가 같은 이름표를 여러 개념에 씀) 표의 계정에 속한 개념, 그다음 회사가 XBRL에서 더 많이 쓴 개념
    out = [("", "", False)] * len(items)
    used_r, used_c = set(), set()
    pairs = [(r, c) for r in range(len(items)) for c in range(len(slots)) if score[r, c] > -np.inf]
    pairs.sort(key=lambda rc: (-round(float(score[rc]), 6), slots[rc[1]]["rank"],
                               -(company or {}).get(slots[rc[1]]["id"], ([], 0))[1]))
    for r, c in pairs:
        if score[r, c] > -np.inf and r not in used_r and c not in used_c:
            used_r.add(r); used_c.add(c)
            out[r] = (slots[c]["id"], slots[c]["path"][0], slots[c]["neg"])
    return out


def _extract_table(t, fy, company: Optional[dict] = None) -> list[dict]:
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
    row_mid = _between(row_hits, {c.r for c in t.cells} - comp_rows) if row_ok else []
    col_mid = _between(col_hits, {c.c for c in t.cells} - comp_cols) if col_ok else []
    item_axis = "row" if (row_ok and len(row_mid) >= len(col_mid)) or not col_ok else "col"
    hits, mid_idx = (row_hits, row_mid) if item_axis == "row" else (col_hits, col_mid)
    account = _account_of(t)
    if not account:
        return []
    if not mid_idx:
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
    first = {}
    for c in t.cells:
        first.setdefault(item_side(c)[0], item_side(c)[1])
    concept_of = _name_items(account, [first[i] for i in mid_idx], company)
    concept_of = dict(zip(mid_idx, concept_of))
    hits = {**hits, **{i: lab for i, (_, lab, _) in concept_of.items()}}
    open_idx = min((i for i, k in hits.items() if k == "기초"), default=None)
    # 기말이 두 번 나오는 표(증감 끝 + 구성요소 뒤)가 있어, 경계는 기초 뒤 '첫' 기말
    close_idx = min((i for i, k in hits.items() if k == "기말" and (open_idx is None or i > open_idx)), default=None)

    cand, has_net = [], set()
    bal: dict[tuple, dict] = defaultdict(dict)
    names = _table_member_names(account, [[p for p in item_side(c)[2] if p not in common] for c in t.cells])
    # 한 줄의 기간은 그 줄의 기초·기말 머리글('당기초'·'전기말')이 정한다. 표 위 문장('당기 및 전기 중 … ① 당기')에서
    # 읽은 기간이 증감 칸에만 붙어 한 줄이 두 기간으로 쪼개지지 않게
    line_of = (lambda c: c.c) if item_axis == "row" else (lambda c: c.r)
    line_per = {}
    for c in t.cells:
        idx, item_parts, _, _ = item_side(c)
        if hits.get(idx) in ("기초", "기말") and item_parts:
            p = detect_period(_norm(item_parts[-1]))
            if p in ("current", "prior", "prior2"):
                line_per.setdefault(line_of(c), p)
    for c in t.cells:
        idx, item_parts, _, label = item_side(c)
        mparts = [p for p in item_side(c)[2] if p not in common]
        member, mmethod = _member_of(mparts, names, account)
        period = line_per.get(line_of(c)) or _period_of(c.period, t.period_hint, fy)
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

    # 증감 검산: 기초 + Σ(기초·기말 사이 항목) = 기말. 짝(기초, 기말, 증감 줄)은 표 단위로 정한다
    for lst in rows_by_key.values():
        lst.sort(key=lambda x: x[0])
    val = {k: {x[0]: x[2].value for x in lst} for k, lst in rows_by_key.items()}
    lab = {x[0]: x[4] for lst in rows_by_key.values() for x in lst}
    bounds = sorted(i for i, k in hits.items() if k in ("기초", "기말"))
    order = sorted({i for v in val.values() for i in v if hits.get(i) not in ("기초", "기말")})
    subtotal = _subtotals(order, bounds, val)
    neg = {i for i, (_, _, n) in concept_of.items() if n}
    pairs = _pairs(hits, first, order, val, subtotal, lab, neg)

    for (member, period), lst in rows_by_key.items():
        v = val[(member, period)]
        state = {}           # 줄 → (상태, 메모)
        flipped, breakdown = set(), set()
        for o, e, mids in pairs:
            if o not in v or e not in v or not mids:      # 증감 줄이 없는 짝('기초 지분율 | 기말 지분율')은 검산 안 함
                continue
            st, nt, bd, fl = _check(v[o], v[e], [i for i in mids if i in v and i not in subtotal], v, lab, neg,
                                    lst[0][2].unit)
            for i in [o, e] + [i for i in mids if i in v]:
                state[i] = (st, nt, o)
            breakdown |= bd
            flipped |= fl
        for idx, item, c, mmethod, label in lst:
            st, nt, pair = state.get(idx, ("unchecked", "", None))
            if idx in subtotal:
                st, nt = "breakdown", "subtotal_row"
            elif idx in breakdown:
                st, nt = "breakdown", "breakdown_row"
            if idx in flipped:      # 검산으로 확인된 경우에만 부호를 바꾼다
                c = SimpleNamespace(**{**vars(c), "value": -c.value,
                                       "value_base": None if c.value_base is None else -c.value_base})
            how = "xbrl" if idx in concept_of else ("vocab" if item else "raw")
            facts.append(_fact(t, "movement", period, account, member, item or _norm(label), c, label,
                               f"axis={item_axis};item={how};member={mmethod}", st, nt,
                               concept_of.get(idx, ("",))[0]))
            facts[-1]["_pair"] = pair       # 다시 검산(_recheck)용, extract가 돌려주기 전에 뗀다

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
                               "verified" if ok else "mismatch", "" if ok else "components_mismatch"))
    # 기초 + 증감 = 기말이 한 자산·기간도 맞지 않는 표는 증감표가 아니다(계정 사전 없이 들어오는 다른 표를 거른다)
    mv = {f["status"] for f in facts if f["kind"] == "movement"}
    if "mismatch" in mv and "verified" not in mv:
        return [f for f in facts if f["kind"] != "movement"]
    return facts


# ─── XBRL 이름표 확인 ───────────────────────────────────────────

# 택소노미 개념 ID (회사 표기와 무관). 축은 이름만, 구성요소는 '접두어_이름'으로 다룬다
_SCOPE_AX = "ConsolidatedAndSeparateFinancialStatementsAxis"
_MEASURE_AX = "CarryingAmountAccumulatedDepreciationAmortisationAndImpairmentAndGrossCarryingAmountAxis"
_SCOPE_OF = {"ConsolidatedMember": "consolidated", "SeparateMember": "separate"}
_X_NET = {"CarryingAmountMember", "ReportedAmountMember"}          # 측정 축 기본값 = 순장부금액
_GENERIC = {"ifrs-full_PropertyPlantAndEquipmentMember", "ifrs-full_AtCostMember"}  # 다른 member와 함께면 구체성 없음
_UNIT_KO = {"KRW": "원", "shares": "주", "pure": ""}
_SMALL_VALUE = 1e8    # 원. 이보다 작은 값은 여러 주석에 흔해 표 확인 근거로 세지 않는다
_BIG_VALUE = 1e9      # 원. 이만한 금액이 같은 연결/별도·같은 날짜로 우연히 일치할 가능성은 낮다
_MIN_SUPPORT = 2
_CHAPTER = re.compile(r"^\d+\.\s*(연결)?\s*재무제표(\s*주석)?$")


def _x_tables(kids: dict, calc: Optional[dict] = None, vocab: Optional[dict] = None,
              defaults: Optional[dict] = None) -> list[dict]:
    """표시 묶음 → 증감 묶음 목록: {account, items: [(개념, 감소 여부, 내역 행 여부)], axes: {축: 도메인 구성요소}}."""
    def below(n, seen=()):
        for k, _ in kids.get(n, ()):
            if k not in seen:
                yield k
                yield from below(k, seen + (k,))

    axes = {}
    for n in kids:
        if n.endswith("Axis"):
            # 합계 구성요소: 정의 구조의 축 기본값(없으면 축 바로 밑)과, 축 아래에서 다른 구성요소를 묶는(자식이 있는) 구성요소
            ax = _local(n)
            base = {defaults[ax]} if defaults and ax in defaults else {k for k, _ in kids.get(n, ())}
            axes[ax] = base | {k for k in below(n) if kids.get(k)}
    out = []
    for parent, ks in kids.items():
        starts = {k for k, r in ks if r == "periodStartLabel"}
        for b in starts & {k for k, r in ks if r == "periodEndLabel"}:
            items = []

            def add(k, r):
                if k == b:
                    return
                if k.endswith("Abstract"):            # 증감 항목을 묶은 머리(…ReconciliationOfChanges…Abstract)
                    for k2, r2 in kids.get(k, ()):
                        add(k2, r2)
                    return
                items.append((k, r.startswith("negated"), False, r))
                for sub in below(k):
                    if not sub.endswith("Abstract"):
                        items.append((sub, False, True, ""))

            # 표시 순서에서 기초와 기말 사이에 있는 자식만 증감 항목('…임대수익' 같은 다른 공시는 밖에 있다)
            i0 = next(i for i, (k, r) in enumerate(ks) if k == b and r == "periodStartLabel")
            i1 = next(i for i, (k, r) in enumerate(ks) if k == b and r == "periodEndLabel")
            for k, r in ks[min(i0, i1) + 1:max(i0, i1)]:
                add(k, r)
            have = {c for c, *_ in items}
            # 기초~기말 밖에 둔 형제라도 다른 공시들이 이 계정의 증감 항목으로 쓰는 개념(KT가 기말 뒤에 둔
            # '재고자산과의 대체', 기말 뒤의 '그 밖의 변동' 등). 내역인지 독립 항목인지는 회사마다 달라 검산으로 정한다
            # (_x_movement, 내역 여부 = "outside"). '…임대수익'처럼 증감 항목으로 안 쓰는 개념은 그대로 밖
            e = (vocab or {}).get(b)
            for k, r in ks[:min(i0, i1)] + ks[max(i0, i1) + 1:]:
                if e and k != b and k not in have and e["items"].get(k, [0, 0])[1] >= _RARE * e["n"]:
                    have.add(k)
                    items.append((k, r.startswith("negated"), "outside", r))
            # 계산 구조상 증감 항목의 합을 이루는 개념 = 내역 행
            for c, _, sub, _ in list(items):
                stack = [] if sub else list((calc or {}).get(c, ()))
                while stack:
                    x = stack.pop()
                    if x not in have:
                        have.add(x)
                        items.append((x, False, True, ""))
                        stack.extend((calc or {}).get(x, ()))
            out.append({"account": b, "items": items, "axes": axes})
    return out


def _x_period(end: str, period_end: str) -> str:
    e, p = date.fromisoformat(end), date.fromisoformat(period_end)
    if e == p:
        return "current"
    if (e.month, e.day) == (p.month, p.day) and p.year - e.year in (1, 2):
        return {1: "prior", 2: "prior2"}[p.year - e.year]
    return end


def _x_net_dims(dims: dict) -> Optional[dict]:
    """측정 축이 순장부금액(기본값)이면 축을 뗀 dims, 다른 측정 구분이면 None(증감 대상 아님)."""
    m = dims.get(_MEASURE_AX)
    if m is None:
        return dims
    if _local(m) in _X_NET:
        return {a: v for a, v in dims.items() if a != _MEASURE_AX}
    return None


def _x_member(dims: dict, axes: dict, doc: _XbrlDoc) -> tuple[str, str]:
    """자산 종류 축 구성요소 → (한글 이름표, 개념). 도메인(축 바로 밑)이나 없음 = 합계 ('', '')."""
    for ax, m in dims.items():
        if ax in (_SCOPE_AX, _MEASURE_AX):
            continue
        if m in axes.get(ax, ()):
            continue
        return doc.label(m), m
    return "", ""


def _x_fact(doc, kind, period, account, member, item, concept, v, unit, decimals, status, note, role, method) -> dict:
    """annotation: *_names = 후보 이름(XBRL 표준 이름표 + 회사가 쓴 이름), precision = decimals를 읽은 반올림 단위."""
    u = _UNIT_KO.get(unit, unit)
    return {"kind": kind, "scope": "", "label_source": "xbrl", "period": period,
            "account": doc.label(account), "account_concept": account, "account_names": doc.names(account),
            "member": member[0], "member_concept": member[1], "member_names": doc.names(member[1]) if member[1] else [],
            "item": item, "item_concept": concept, "item_names": doc.names(concept) if concept else [],
            "value": v, "value_eok": v / 1e8 if unit == "KRW" else None, "unit": u, "decimals": decimals,
            "precision": _precision(decimals, u),
            "status": status, "note": note, "method": method, "label": item,
            "table_no": None, "r": None, "c": None, "src_ref": doc.path, "page": None, "sheet": None,
            "section": role}


def _precision(decimals, unit: str) -> str:
    """decimals → 반올림 단위('-6' + 원 → '백만원', 'INF' → '정확'). 금액 값 자체는 이미 원 단위."""
    if decimals in (None, "INF"):
        return "정확" if decimals == "INF" else ""
    try:
        d = int(decimals)
    except ValueError:
        return ""
    if unit == "원" and d <= 0:
        return {0: "원", -3: "천원", -6: "백만원", -8: "억원", -9: "십억원", -12: "조원"}.get(d, f"10^{-d}원")
    return f"소수 {d}자리" if d > 0 else f"10^{-d}{unit}"


def extract_xbrl(path) -> list[dict]:
    """XBRL zip → 증감(kind="movement")·잔액(kind="balance") 사실. 표시 구조가 없으면 []."""
    doc = _xdoc(str(path))
    if not doc.period_end:
        return []
    out, seen = [], set()
    for role, kids in doc.links:
        for tb in _x_tables(kids, doc.calc, xbrl_vocab.load()["accounts"], doc.defaults):
            out.extend(_x_movement(doc, role, tb, seen))
            out.extend(_x_balance(doc, role, tb, seen))
    return _dedupe_movements(out)


def _dedupe_movements(facts: list[dict]) -> list[dict]:
    """같은 증감(계정·자산 종류·연결/별도·기간·기초·기말 값이 같음)이 여러 표시 묶음에 있으면 하나만 남긴다:
    검산이 맞는 쪽, 그다음 항목이 많은 쪽. 회사가 같은 증감을 연결·별도 주석 묶음에 나눠 넣으면서 한쪽엔 일부 항목만
    태깅하는 경우가 있다."""
    groups = defaultdict(list)
    for i, f in enumerate(facts):
        if f["kind"] == "movement":
            groups[(f["section"], f["account_concept"], f["member_concept"], f["scope"], f["period"])].append(i)
    best: dict[tuple, tuple] = {}
    for k, idx in groups.items():
        o = next((facts[i]["value"] for i in idx if facts[i]["item"] == "기초"), None)
        c = next((facts[i]["value"] for i in idx if facts[i]["item"] == "기말"), None)
        same = (k[1], k[2], k[3], k[4], o, c)
        rank = (facts[idx[0]]["status"] == "verified" or any(facts[i]["status"] == "verified" for i in idx), len(idx))
        if same not in best or rank > best[same][0]:
            best[same] = (rank, k)
    keep_groups = {k for _, k in best.values()}
    return [f for f in facts if f["kind"] != "movement"
            or (f["section"], f["account_concept"], f["member_concept"], f["scope"], f["period"]) in keep_groups]


def _step(decimals) -> float:
    """decimals('-6') → 반올림 단위(1,000,000). 모르거나 INF면 0."""
    try:
        return 10.0 ** -int(decimals)
    except (TypeError, ValueError):
        return 0.0


def _x_movement(doc: _XbrlDoc, role: str, tb: dict, seen: set) -> list[dict]:
    acc, axes = tb["account"], tb["axes"]
    allowed = set(axes) | {_SCOPE_AX, _MEASURE_AX}
    # (scope, 자산 종류 축들, 기간 끝) → {시작: {item 개념: {측정 축 명시 여부: 값}}}
    # 같은 개념·기간 값이 측정 축('공시금액')이 붙은 것과 안 붙은 것 둘이면(다른 공시의 값이 섞임) 묶음마다
    # 기초 + 증감과 기말의 차이가 가장 작은 쪽을 고른다(아래). 회사마다 증감표에 쓴 쪽이 다르다
    groups = defaultdict(lambda: defaultdict(lambda: defaultdict(dict)))
    meta, negp = {}, {}
    fits = lambda f: (_MEASURE_AX in f[0]) == (_MEASURE_AX in axes)
    # 다른 공시들이 이 계정의 증감 항목으로 쓰는 개념(후보 목록)과 감소로 표시한 비율 — 이 공시에 감소 표시가 빠졌거나
    # 항목을 증감 묶음 밖에 태깅했을 때 쓴다(아래)
    known = {c: x for c, x in (xbrl_vocab.load()["accounts"].get(acc) or {"items": {}, "n": 0})["items"].items()}
    n_acc = (xbrl_vocab.load()["accounts"].get(acc) or {"n": 0})["n"]
    for c, neg, sub, _ in tb["items"]:
        negp[c] = neg
        for dims, s, end, v, unit, dec in doc.facts.get(c, ()):
            explicit = _MEASURE_AX in dims
            dims = _x_net_dims(dims)
            if not s or dims is None or not set(dims) <= allowed:
                continue
            key = (dims.get(_SCOPE_AX, ""), tuple(sorted((a, m) for a, m in dims.items() if a != _SCOPE_AX)), end)
            groups[key][s][c][explicit] = v
            meta[c] = (unit, dec, sub)
    neg_vocab = {c: (known[c][2] * 2 >= known[c][1]) if c in known else negp[c] for c in negp}
    bal = defaultdict(dict)
    for dims, s, end, v, unit, dec in sorted(doc.facts.get(acc, ()), key=fits):
        dims = _x_net_dims(dims)
        if s is None and dims is not None and set(dims) <= allowed:
            bal[(dims.get(_SCOPE_AX, ""), tuple(sorted((a, m) for a, m in dims.items() if a != _SCOPE_AX)))][end] = (v, unit, dec)
    out = []
    for (scope, dkey, end), by_start in groups.items():
        start = min(by_start)                    # 같은 끝 날짜에 기간이 여럿이면 누계(가장 이른 시작)
        open_d = (date.fromisoformat(start) - timedelta(days=1)).isoformat()
        b = bal.get((scope, dkey), {})
        if open_d not in b or end not in b:
            continue
        pref = _MEASURE_AX in axes
        # 후보: 측정 축 명시 값 우선/아님 × 감소 부호를 이 공시 표시대로/다른 공시 대다수대로. 차이가 가장 작은 쪽
        variants = [{c: (-1 if sign[c] else 1) * d.get(p, d.get(not p)) for c, d in by_start[start].items()}
                    for sign in (negp, neg_vocab) for p in (pref, not pref)]
        # 하위 항목·기초~기말 밖 항목은 내역인지 독립 항목인지 회사마다 달라, 뺀 것(기본)·밖 항목만 넣은 것·전부 넣은
        # 것 중 기초 + 증감과 기말의 차이가 가장 작은 쪽(같으면 앞쪽)
        subs = {c: meta[c][2] for c in by_start[start] if c in meta and meta[c][2]}
        inc_sets = [set(), {c for c, k in subs.items() if k == "outside"}, set(subs)]
        resid = lambda vs, inc: b[end][0] - b[open_d][0] - sum(v for c, v in vs.items() if c not in subs or c in inc)
        vals, inc = min(((vs, inc) for inc in inc_sets for vs in variants), key=lambda x: abs(resid(*x)))
        vals = dict(vals)
        for c in subs:
            meta[c] = meta[c][:2] + (c not in inc,)
        added = []
        if abs(resid(vals, inc)) > max(2.0, abs(b[end][0]) * 0.001):
            # 증감 묶음 밖에 태깅한 항목: 같은 연결/별도·자산 종류·기간의 사실 중 이 계정의 증감 항목으로 쓰이는 개념이고
            # 값이 모자란 금액과 같으면 넣는다
            gap = resid(vals, inc)
            gtol = max(2.0, 0.5 * (_step(b[open_d][2]) + _step(b[end][2])))

            def ctx_hits(cands, sign_of):
                out = []
                for c in cands:
                    for dims, s0, e0, v, unit, dec in doc.facts.get(c, ()):
                        dims = _x_net_dims(dims)
                        sv = sign_of(c) * v
                        if s0 == start and e0 == end and dims is not None and dims.get(_SCOPE_AX, "") == scope \
                                and tuple(sorted((a, m) for a, m in dims.items() if a != _SCOPE_AX)) == dkey \
                                and abs(sv - gap) <= gtol + 0.5 * _step(dec):
                            out.append((c, sv, unit, dec))
                return out
            # 먼저 이 계정의 증감 항목(후보 목록), 없으면 같은 맥락의 모든 기간 사실. 값이 맞는 것이 하나뿐일 때만
            hits = ctx_hits([c for c, x in known.items() if c not in vals and c != acc and x[1] >= _RARE * n_acc],
                            lambda c: -1 if known[c][2] * 2 >= known[c][1] else 1)
            if not hits:
                hits = ctx_hits([c for c in doc.facts if c not in vals and c != acc], lambda c: 1) + \
                    ctx_hits([c for c in doc.facts if c not in vals and c != acc], lambda c: -1)
            if len({h[0] for h in hits}) == 1:
                c, sv, unit, dec = hits[0]
                vals[c] = sv
                meta[c] = (unit, dec, False)
                added.append(c)
        key = (role, acc, scope, dkey, end)
        if key in seen:
            continue
        seen.add(key)
        dims = dict(dkey)
        member = _x_member(dims, axes, doc)
        period = _x_period(end, doc.period_end)
        top = [c for c in vals if not meta[c][2]]
        # 허용 오차: 기말의 0.1%와, 더한 값들의 반올림 단위(decimals) 절반의 합 중 큰 쪽
        rnd = 0.5 * sum(_step(x) for x in [b[open_d][2], b[end][2]] + [meta[c][1] for c in top])
        tol = max(2.0, abs(b[end][0]) * 0.001, rnd)
        s = b[open_d][0] + sum(vals[c] for c in top)
        flipped, dropped = [], []
        if abs(s - b[end][0]) > tol:
            # 감소를 양수로 태깅한 항목(회사마다 관례가 다름)과 이중 계산 항목(세전·법인세·세후가 함께 있음 등):
            # 가장 적은 수의 항목을 뒤집거나(×−1) 빼서(×0) 반올림 오차 안에서 맞는 조합이 하나뿐이면 쓴다
            nz = [c for c in top if vals[c]]
            for k in range(1, min(len(nz), 3) + 1):
                hits = []
                for cs in itertools.combinations(nz, k):
                    for ops in itertools.product((-1, 0), repeat=k):
                        delta = sum((op - 1) * vals[c] for c, op in zip(cs, ops))
                        if abs(s + delta - b[end][0]) <= max(2.0, rnd):
                            hits.append(list(zip(cs, ops)))
                if hits:
                    if len(hits) == 1:
                        for c, op in hits[0]:
                            if op == -1:
                                vals[c] = -vals[c]
                                flipped.append(c)
                            else:
                                dropped.append(c)
                                meta[c] = meta[c][:2] + (True,)
                        top = [c for c in vals if not meta[c][2]]
                        s = b[open_d][0] + sum(vals[c] for c in top)
                    break
        ok = abs(s - b[end][0]) <= tol
        st = "verified" if ok else "mismatch"
        note = "" if ok else "rollforward_mismatch"
        rows = [("기초", "", b[open_d], st, note)]
        for c, _, sub, role_c in tb["items"] + [(c, False, False, "") for c in added]:
            if c in vals:
                unit, dec, _ = meta[c]
                sub = meta[c][2] if c in meta else sub
                # 메모 = 처리 이유 코드(첫 번째로 해당하는 것), 없으면 묶음의 검산 메모
                code = next((k for k, hit in (("double_count", c in dropped), ("breakdown_row", bool(sub)),
                                              ("out_of_group_added", c in added), ("sign_flipped", c in flipped)) if hit), note)
                rows.append((doc.label(c, role_c), c, (vals[c], unit, dec), "breakdown" if sub else st, code))
        rows.append(("기말", "", b[end], st, note))
        for item, concept, (v, unit, dec), st2, nt in rows:
            f = _x_fact(doc, "movement", period, acc, member, item, concept, v, unit, dec, st2, nt, role, "xbrl_presentation")
            f["scope"] = _SCOPE_OF.get(_local(scope), "") if scope else ""
            out.append(f)
    return out


def _x_balance(doc: _XbrlDoc, role: str, tb: dict, seen: set) -> list[dict]:
    """측정 축이 있는 계정 시점 값: 총액 + 누계액(+손상·보조금) = 순액."""
    acc, axes = tb["account"], tb["axes"]
    if _MEASURE_AX not in axes:
        return []
    groups = defaultdict(dict)
    for dims, s, end, v, unit, dec in doc.facts.get(acc, ()):
        if s is not None or not set(dims) <= set(axes) | {_SCOPE_AX}:
            continue
        ms = dims.get(_MEASURE_AX) or "ifrs-full_CarryingAmountMember"
        key = (dims.get(_SCOPE_AX, ""), tuple(sorted((a, m) for a, m in dims.items() if a not in (_SCOPE_AX, _MEASURE_AX))), end)
        groups[key][ms] = (v, unit, dec)
    out = []
    for (scope, dkey, end), ms in groups.items():
        net = next((ms[m] for m in ms if _local(m) in _X_NET), None)
        comps = {m: x for m, x in ms.items() if _local(m) not in _X_NET}
        if net is None or not comps:
            continue
        key = ("balance", acc, scope, dkey, end)
        if key in seen:
            continue
        seen.add(key)
        total = sum(x[0] for x in comps.values())
        ok = abs(total - net[0]) <= max(2.0, abs(net[0]) * 0.001)
        per = _x_period(end, doc.period_end)
        per = {"current": "current_end", "prior": "prior_end", "prior2": "prior2_end"}.get(per, per)
        member = _x_member(dict(dkey), axes, doc)
        for m, (v, unit, dec) in ms.items():
            f = _x_fact(doc, "balance", per, acc, member, doc.label(m), m, v, unit, dec,
                      "verified" if ok else "mismatch", "" if ok else "components_mismatch",
                      role, "xbrl_presentation")
            f["scope"] = _SCOPE_OF.get(_local(scope), "") if scope else ""
            out.append(f)
    return out


class _Instance:
    """XBRL 인스턴스 하나의 시점 값: 값(원) → [{scope, instant, members, concept}] + 한글 라벨."""

    def __init__(self, path):
        d = _xdoc(str(path))
        self.label = {c: r.get("label") or next(iter(r.values())) for c, r in d.labels.items() if r}
        self.by_val: dict[int, list[dict]] = defaultdict(list)
        for concept, fs in d.facts.items():
            for dims, s, end, v, _, _ in fs:
                if s is None and end:
                    self.by_val[round(v)].append({
                        "scope": _SCOPE_OF.get(_local(dims.get(_SCOPE_AX, "")), ""), "instant": end,
                        "members": tuple(sorted((a, m) for a, m in dims.items() if a not in (_SCOPE_AX, _MEASURE_AX))),
                        "concept": concept})
        self._keys = sorted(self.by_val)

    def find(self, value: float, tol: float) -> list[dict]:
        """원문 단위(백만원·천원)로 반올림된 값 → 반올림 범위 안의 XBRL 사실 전부."""
        lo = bisect.bisect_left(self._keys, value - tol)
        hi = bisect.bisect_right(self._keys, value + tol)
        return [h for k in self._keys[lo:hi] for h in self.by_val[k]]


def _instance(path) -> Optional[_Instance]:
    try:
        return _Instance(path)
    except Exception:      # noqa: BLE001  깨진 XBRL 하나 때문에 전체를 멈추지 않는다
        return None


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
        kinds = {"prior" if k == "prior_q" else k for k in periods_in(cap)}      # 전분기 = 전년 같은 분기
        # '당기 및 전기 중 … 변동내역'처럼 둘 다 나오면 좁히지 않는다 (한 표에 당기·전기 열이 함께 있다).
        # 캡션에 기간 낱말이 아예 없으면 표 분석의 기간 힌트(소속 제목의 '<당기말>' 등)로 좁힌다 — 토지처럼 두 해 값이
        # 같으면 XBRL 값만으로는 당기·전기를 가를 수 없다
        per = kinds if kinds in ({"current"}, {"prior"}) else set()
        if not kinds and t.get("period_hint") in ("current", "prior"):
            per = {t["period_hint"]}
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
            spec = [m for _, m in mem if m not in _GENERIC] or [m for _, m in mem]
            f["member"] = member
            f["member_concept"] = spec[-1].replace(":", "_") if member and not member.endswith("(소계)") else ""
            if note:
                f["note"] = f"{f['note']};{note}" if f["note"] else note
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
    return old, "xbrl_member_kept"


def _recheck(touched: dict) -> None:
    """이름표를 바꾼 축마다 기초 + Σ증감 = 기말을 다시 검산. 추출 때 정한 짝(기초·기말·증감 줄) 안에서만."""
    groups = defaultdict(list)
    for (tno, ax), fs in touched.items():
        for f in fs:
            if f.get("_pair") is not None:
                groups[(tno, ax, f["_pair"])].append(f)
    for fs in groups.values():
        o = next((f for f in fs if f["item"] == "기초"), None)
        c = next((f for f in fs if f["item"] == "기말"), None)
        if not o or not c or o["value"] is None or c["value"] is None:
            continue
        mid = [f for f in fs if f is not o and f is not c and f["status"] != "breakdown" and f["value"] is not None
               and not is_total_label((f["label"] or "").split(" > ")[-1])]
        s = o["value"] + sum(f["value"] for f in mid)
        ok = abs(s - c["value"]) <= max(2.0 * 1e6, abs(c["value"]) * 0.001)
        for f in fs:
            new = "verified" if ok else "mismatch"
            if f["status"] in ("verified", "mismatch", "unchecked") and new != f["status"]:
                f["status"] = new
                f["note"] = "rechecked" if ok else "rollforward_mismatch"


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
                f["member"], f["member_concept"], f["label_source"] = member, "", "document"
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
