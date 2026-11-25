"""XBRL 형식 읽기 — DART XBRL zip(인스턴스 + 이름표·표시·계산·정의 연결선)을 읽는다. 해석하지 않는다.

    _xdoc(path)      # → _XbrlDoc: 사실(개념·축·기간·값), 한글 이름표, 표시·계산 구조, 축 기본값
                     #   (facts.extract_xbrl·_Instance, xbrl_vocab이 쓴다)
    read_xbrl(path)  # → 원본 그대로의 행 목록(맥락·축·단위·숫자/텍스트 사실·한영 이름표·연결선)
                     #   (pipelines/loaders/xbrl.py가 DuckDB에 적재)
    filing_key(path)    # → (회사 식별자, 기간 말): 인스턴스의 맥락만 읽는다(정정공시 교체용)
    read_taxonomy(dir)  # → 택소노미 값 개념마다 기간 종류·값 형식·언어별 표준 이름표

개념·구성요소 이름은 'ifrs-full_PropertyPlantAndEquipment' 형식(연결선·이름표 파일의 href와 같은 형식).
연결/별도·기초/기말·증감 같은 뜻 판정은 facts.py가 한다.
"""
from __future__ import annotations

import math
import re
import zipfile
from collections import defaultdict
from functools import lru_cache
from pathlib import Path
from typing import Optional

_XI = "{http://www.xbrl.org/2003/instance}"
_XD = "{http://xbrl.org/2006/xbrldi}"
_LB = "{http://www.xbrl.org/2003/linkbase}"
_XL = "{http://www.w3.org/1999/xlink}"
_XS = "{http://www.w3.org/2001/XMLSchema}"
_LANG = "{http://www.w3.org/XML/1998/namespace}lang"
# 숫자 값을 갖는 XBRL 항목 형식(xbrli·DTR 형식 이름)
_NUMERIC_TYPE = re.compile(r"monetary|decimal|shares|percent|perShare|pure|integer|float|double", re.I)


def _xml(raw: bytes):
    from lxml import etree
    return etree.fromstring(raw, etree.XMLParser(recover=True, huge_tree=True))


def _local(concept: str) -> str:
    return concept.split("_", 1)[-1].split(":")[-1]


class _XbrlDoc:
    """zip 하나: 사실(개념·문맥·단위·값), 한글 이름표, 표시 구조."""

    def __init__(self, path):
        self.path = str(path)
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            inst = next(n for n in names if n.endswith(".xbrl"))
            pre = next((n for n in names if n.endswith("_pre.xml")), None)
            cal = next((n for n in names if n.endswith("_cal.xml")), None)
            dfn = next((n for n in names if n.endswith("_def.xml")), None)
            lab = next((n for n in names if re.search(r"lab-ko\.xml$|lab_.*-ko_.*\.xml$", n)), None)
            self.labels = self._labels(z.read(lab)) if lab else {}
            self.links = self._presentation(z.read(pre)) if pre else []
            self.calc = self._calculation(z.read(cal)) if cal else {}
            self.defaults = self._defaults(z.read(dfn)) if dfn else {}
            self._instance(_xml(z.read(inst)))

    @staticmethod
    def _labels(raw: bytes) -> dict[str, dict[str, str]]:
        """개념 → {이름표 역할: 한글}."""
        out: dict[str, dict[str, str]] = defaultdict(dict)
        for link in _xml(raw).iter(_LB + "labelLink"):
            loc = {x.get(_XL + "label"): x.get(_XL + "href").split("#")[-1] for x in link.iter(_LB + "loc")}
            lab = {x.get(_XL + "label"): ((x.get(_XL + "role") or "").split("/")[-1], (x.text or "").strip())
                   for x in link.iter(_LB + "label")}
            for a in link.iter(_LB + "labelArc"):
                c, (role, text) = loc.get(a.get(_XL + "from")), lab.get(a.get(_XL + "to"), ("", ""))
                if c and text:
                    out[c].setdefault(role, text)
        return out

    @staticmethod
    def _defaults(raw: bytes) -> dict[str, str]:
        """정의 구조의 축 기본값(dimension-default): 축 이름 → 합계 구성요소."""
        out = {}
        for link in _xml(raw).iter(_LB + "definitionLink"):
            loc = {x.get(_XL + "label"): x.get(_XL + "href").split("#")[-1] for x in link.iter(_LB + "loc")}
            for a in link.iter(_LB + "definitionArc"):
                if (a.get(_XL + "arcrole") or "").endswith("/dimension-default"):
                    out[_local(loc[a.get(_XL + "from")])] = loc[a.get(_XL + "to")]
        return out

    @staticmethod
    def _calculation(raw: bytes) -> dict[str, set[str]]:
        """계산 구조: 합 개념 → 그 합을 이루는 개념들(모든 계산 묶음을 합친 것)."""
        out: dict[str, set[str]] = defaultdict(set)
        for link in _xml(raw).iter(_LB + "calculationLink"):
            loc = {x.get(_XL + "label"): x.get(_XL + "href").split("#")[-1] for x in link.iter(_LB + "loc")}
            for a in link.iter(_LB + "calculationArc"):
                out[loc[a.get(_XL + "from")]].add(loc[a.get(_XL + "to")])
        return dict(out)

    @staticmethod
    def _presentation(raw: bytes) -> list[tuple[str, dict]]:
        """[(표시 묶음 role, 부모 → [(자식, preferredLabel 역할)])]."""
        out = []
        for link in _xml(raw).iter(_LB + "presentationLink"):
            loc = {x.get(_XL + "label"): x.get(_XL + "href").split("#")[-1] for x in link.iter(_LB + "loc")}
            kids = defaultdict(list)
            arcs = sorted(link.iter(_LB + "presentationArc"), key=lambda a: float(a.get("order") or 0))
            for a in arcs:
                kids[loc[a.get(_XL + "from")]].append((loc[a.get(_XL + "to")], (a.get("preferredLabel") or "").split("/")[-1]))
            out.append((link.get(_XL + "role") or "", dict(kids)))
        return out

    def _instance(self, t) -> None:
        from lxml import etree
        ctx = {}
        for c in t.iter(_XI + "context"):
            dims = {_local(m.get("dimension")): (m.text or "").strip().replace(":", "_")
                    for m in c.iter(_XD + "explicitMember")}
            p = c.find(_XI + "period")
            inst, s, e = (p.find(_XI + k) if p is not None else None for k in ("instant", "startDate", "endDate"))
            ctx[c.get("id")] = (dims, (s.text.strip() if s is not None else None),
                                (inst if inst is not None else e).text.strip() if (inst is not None or e is not None) else None)
        units = {}
        for u in t.iter(_XI + "unit"):
            m = u.find(f".//{_XI}measure")
            units[u.get("id")] = (m.text or "").split(":")[-1] if m is not None else ""
        # (개념) → [(dims, 시작, 끝, 값, 단위, decimals)]
        self.facts: dict[str, list[tuple]] = defaultdict(list)
        for e in t:
            if not isinstance(e.tag, str) or e.get("unitRef") is None or e.get("contextRef") not in ctx:
                continue
            try:
                v = float(e.text)
            except (TypeError, ValueError):
                continue
            dims, s, end = ctx[e.get("contextRef")]
            self.facts[f"{e.prefix}_{etree.QName(e).localname}"].append(
                (dims, s, end, v, units.get(e.get("unitRef"), ""), e.get("decimals")))
        ends = [end for fs in self.facts.values() for _, s, end, *_ in fs if s and end]
        self.period_end = max(ends) if ends else None

    def label(self, concept: str, role: str = "") -> str:
        """한글 이름표. role(표시 구조 연결선의 preferredLabel, 예: terseLabel)이 있으면 회사가 그 표에 쓴 이름."""
        d = self.labels.get(concept, {})
        t = d.get(role) or d.get("label") or next(iter(d.values()), _local(concept))
        return re.sub(r"\s*\[(구성요소|member|개요)\]\s*$", "", t, flags=re.I)

    def names(self, concept: str) -> list[str]:
        """후보 이름: 그 개념의 한글 이름표 전부(표준 이름표 + 회사가 쓴 이름, 설명문 제외)."""
        d = self.labels.get(concept, {})
        return list(dict.fromkeys(self.label(concept, r) for r in d if r != "commentaryGuidance"))


@lru_cache(maxsize=64)
def _xdoc(path: str) -> _XbrlDoc:
    return _XbrlDoc(path)


# ─── 원본 그대로 (DuckDB 원본 적재: pipelines/loaders/xbrl.py) ─────────────────────

# 적재하지 않는 개념: 공시 작성 담당자 연락처(이메일·전화·팩스 등) — 개인정보
_PRIVATE_PREFIX = ("dart-gcd_ContactPersonnel",)


def _qname(q: str) -> str:
    """'ifrs-full:LandMember' → 'ifrs-full_LandMember' (표시·계산·정의 구조·이름표의 개념 이름과 같은 형식)."""
    return q.strip().replace(":", "_", 1)


def _arcs(raw: bytes, link: str, arc: str) -> list[dict]:
    """연결선 파일(_pre·_cal·_def) → 연결선마다 {role_uri, src, dst, arcrole, ord, preferred_label, weight}."""
    out = []
    for lk in _xml(raw).iter(_LB + link):
        loc = {x.get(_XL + "label"): x.get(_XL + "href").split("#")[-1] for x in lk.iter(_LB + "loc")}
        for a in lk.iter(_LB + arc):
            out.append({"role_uri": lk.get(_XL + "role") or "", "src": loc.get(a.get(_XL + "from")),
                        "dst": loc.get(a.get(_XL + "to")), "arcrole": a.get(_XL + "arcrole") or "",
                        "ord": float(a.get("order")) if a.get("order") else None,
                        "preferred_label": a.get("preferredLabel") or "",
                        "weight": float(a.get("weight")) if a.get("weight") else None})
    return out


def read_xbrl(path) -> dict:
    """XBRL zip 하나를 바꾸지 않고 읽는다(값 환산·부호 변경·해석 없음). 개념 이름은 'ifrs-full_…' 형식.
    {"entity", "contexts", "context_dims", "units", "facts_numeric", "facts_text", "labels",
     "presentation", "calculation", "definition", "roles"}. roles = 스키마의 묶음(roleType) 주소와 이름. 개인정보 개념(_PRIVATE_PREFIX)은 빼고, 뺀 개수를 "skipped"에 둔다."""
    from lxml import etree
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        get = lambda suffix: next((z.read(n) for n in names if n.endswith(suffix)), None)
        inst = _xml(get(".xbrl"))
        labels = []
        for lang in ("ko", "en"):
            raw = next((z.read(n) for n in names if re.search(rf"lab-{lang}\.xml$|lab_.*-{lang}_.*\.xml$", n)), None)
            for c, roles in (_XbrlDoc._labels(raw) if raw else {}).items():
                labels += [{"concept": c, "lang": lang, "role": r, "text": t} for r, t in roles.items()]
        links = {k: _arcs(get(suffix), link, arc) if get(suffix) else []
                 for k, suffix, link, arc in (("presentation", "_pre.xml", "presentationLink", "presentationArc"),
                                              ("calculation", "_cal.xml", "calculationLink", "calculationArc"),
                                              ("definition", "_def.xml", "definitionLink", "definitionArc"))}
        roles = [{"role_uri": r.get("roleURI"), "definition": (r.findtext(_LB + "definition") or "").strip()}
                 for n in names if n.endswith(".xsd") for r in _xml(z.read(n)).iter(_LB + "roleType")]
    entity, contexts, dims = "", [], []
    for c in inst.iter(_XI + "context"):
        cid = c.get("id")
        ident = c.find(f"{_XI}entity/{_XI}identifier")
        entity = entity or (ident.text or "").strip() if ident is not None else entity
        p = c.find(_XI + "period")
        inst_d, s, e = (p.find(_XI + k) if p is not None else None for k in ("instant", "startDate", "endDate"))
        contexts.append({"context_id": cid, "period_type": "instant" if inst_d is not None else "duration",
                         "start_date": s.text.strip() if s is not None else None,
                         "end_date": (inst_d if inst_d is not None else e).text.strip()
                         if (inst_d is not None or e is not None) else None})
        dims += [{"context_id": cid, "axis": _qname(m.get("dimension")), "member": _qname(m.text or "")}
                 for m in c.iter(_XD + "explicitMember")]
    units = []
    for u in inst.iter(_XI + "unit"):
        num = u.find(f"{_XI}divide/{_XI}unitNumerator/{_XI}measure")
        den = u.find(f"{_XI}divide/{_XI}unitDenominator/{_XI}measure")
        if num is None:
            num = u.find(f"{_XI}measure")
        units.append({"unit_id": u.get("id"), "numerator": (num.text or "").strip() if num is not None else "",
                      "denominator": (den.text or "").strip() if den is not None else ""})
    nums, texts, skipped = [], [], 0
    for seq, e in enumerate(inst):
        if not isinstance(e.tag, str) or e.get("contextRef") is None:
            continue
        concept = f"{e.prefix}_{etree.QName(e).localname}"
        if concept.startswith(_PRIVATE_PREFIX):
            skipped += 1
            continue
        raw_v = e.text or ""
        if e.get("unitRef") is not None:
            try:
                v = float(raw_v) if raw_v.strip() else None
            except ValueError:
                v = None
            if v is not None and not math.isfinite(v): 
                v = None
            nums.append({"seq": seq, "concept": concept, "context_id": e.get("contextRef"), "unit_id": e.get("unitRef"),
                         "decimals": e.get("decimals"), "value_text": raw_v, "value": raw_v.strip() if v is not None else None})
        else:
            texts.append({"seq": seq, "concept": concept, "context_id": e.get("contextRef"),
                          "lang": e.get("{http://www.w3.org/XML/1998/namespace}lang"), "value_text": raw_v})
    return {"entity": entity, "contexts": contexts, "context_dims": dims, "units": units,
            "facts_numeric": nums, "facts_text": texts, "labels": labels, "roles": roles, "skipped": skipped, **links}


def filing_key(path) -> tuple[str, Optional[str]]:
    """XBRL zip → (회사 식별자, 기간 말 = 기간 맥락의 가장 늦은 끝 날짜)."""
    import io
    from lxml import etree
    with zipfile.ZipFile(path) as z:
        inst = z.read(next(n for n in z.namelist() if n.endswith(".xbrl")))
    entity, ends = "", []
    for _, c in etree.iterparse(io.BytesIO(inst), tag=_XI + "context", recover=True, huge_tree=True):
        ident = c.find(f"{_XI}entity/{_XI}identifier")
        if not entity and ident is not None:
            entity = (ident.text or "").strip()
        start, end = c.find(f"{_XI}period/{_XI}startDate"), c.find(f"{_XI}period/{_XI}endDate")
        if start is not None and end is not None and end.text:
            ends.append(end.text.strip())
        c.clear()
    return entity, max(ends) if ends else None


def _xbrl_period_end(path) -> Optional[str]:
    """XBRL zip의 보고 기간 말(가장 늦은 기간 끝 날짜)."""
    return _xdoc(str(path)).period_end


def read_taxonomy(root) -> dict[str, dict]:
    """택소노미 폴더(*.xsd + lab*.xml) → 값 개념(추상·축·구성요소 제외)마다
    {"period": instant/duration, "vtype": number/text, "labels": {언어: 표준 이름표}}.
    여러 버전을 합치고, 같은 개념의 이름표는 경로(버전 날짜)가 늦은 파일 것을 쓴다."""
    out: dict[str, dict] = {}
    root = Path(root)
    for xsd in sorted(root.rglob("*.xsd")):
        for e in _xml(xsd.read_bytes()).iter(_XS + "element"):
            if e.get("id") and e.get("abstract") != "true" and (e.get("substitutionGroup") or "").endswith(":item"):
                t = (e.get("type") or "").split(":")[-1]
                out[e.get("id")] = {"period": e.get(_XI + "periodType"),
                                    "vtype": "number" if _NUMERIC_TYPE.search(t) else "text", "labels": {}}
    for lab in sorted(root.rglob("lab*.xml"), reverse=True):
        for link in _xml(lab.read_bytes()).iter(_LB + "labelLink"):
            loc = {x.get(_XL + "label"): (x.get(_XL + "href") or "").split("#")[-1] for x in link.iter(_LB + "loc")}
            text = {x.get(_XL + "label"): (x.get(_LANG), (x.text or "").strip()) for x in link.iter(_LB + "label")
                    if (x.get(_XL + "role") or "").endswith("/label")}
            for a in link.iter(_LB + "labelArc"):
                k, (lang, t) = loc.get(a.get(_XL + "from")), text.get(a.get(_XL + "to"), (None, ""))
                if k in out and lang and t:
                    out[k]["labels"].setdefault(lang, t)
    return out
