"""증감표 후보 목록: DART XBRL 파일의 표시 구조와 한글 이름표에서 모은 계정·항목·자산 종류.

손으로 쓴 단어 목록 대신 쓴다. 증감표 계정 = 표시 구조에서 같은 부모 아래 '기초 ○○'(periodStartLabel)와
'기말 ○○'(periodEndLabel)이 붙은 개념. 그 부모의 다른 자식 = 증감 항목(negatedLabel이면 감소),
같은 표시 묶음의 축 구성요소 = 자산 종류·측정 구분. 회사가 만든 개념(entity…)은 넣지 않는다.

    python -m parsers.xbrl_vocab <XBRL zip 폴더> [출력 경로]     # 폴더 아래 *.zip 전부 (하위 폴더 포함)

zip은 OpenDART 재무제표 원본파일(XBRL) API로 받은 것. 출력 기본값은 이 폴더의 xbrl_vocab.json.
"""
from __future__ import annotations

import json
import sys
import zipfile
from collections import Counter, defaultdict
from functools import lru_cache
from pathlib import Path

PATH = Path(__file__).with_name("xbrl_vocab.json")

_L = "{http://www.xbrl.org/2003/linkbase}"
_X = "{http://www.w3.org/1999/xlink}"
_SCOPE_AX = "ConsolidatedAndSeparateFinancialStatementsAxis"
_MEASURE_AX = "CarryingAmountAccumulatedDepreciationAmortisationAndImpairmentAndGrossCarryingAmountAxis"


def _std(concept: str) -> bool:
    return not concept.startswith("entity")


def _xml(raw: bytes):
    from lxml import etree
    return etree.fromstring(raw, etree.XMLParser(recover=True, huge_tree=True))


def _labels(raw: bytes) -> dict[str, dict[str, str]]:
    """개념 → {이름표 역할: 한글}. 역할: label, periodStartLabel, periodEndLabel, totalLabel …"""
    out: dict[str, dict[str, str]] = defaultdict(dict)
    for link in _xml(raw).iter(_L + "labelLink"):
        loc = {x.get(_X + "label"): x.get(_X + "href").split("#")[-1] for x in link.iter(_L + "loc")}
        lab = {x.get(_X + "label"): ((x.get(_X + "role") or "").split("/")[-1], (x.text or "").strip())
               for x in link.iter(_L + "label")}
        for a in link.iter(_L + "labelArc"):
            c, (role, text) = loc.get(a.get(_X + "from")), lab.get(a.get(_X + "to"), ("", ""))
            if c and text:
                out[c].setdefault(role, text)
    return out


def build(zip_dir: str | Path) -> dict:
    """zip 폴더 → 후보 목록. 개념마다 몇 개 공시에서 그 계정의 증감 항목·자산 종류로 쓰였는지 센다."""
    acc = defaultdict(lambda: {"n": 0, "items": Counter(), "neg": Counter(), "members": Counter(), "measures": Counter()})
    names: dict[str, dict[str, str]] = defaultdict(dict)
    n_zip = 0
    for z in sorted(Path(zip_dir).rglob("*.zip")):
        try:
            with zipfile.ZipFile(z) as f:
                files = f.namelist()
                pre = next((n for n in files if n.endswith("_pre.xml")), None)
                lab = next((n for n in files if n.endswith("lab-ko.xml")), None)
                if not pre or not lab:
                    continue
                tree, labels = _xml(f.read(pre)), _labels(f.read(lab))
        except (zipfile.BadZipFile, OSError):
            continue
        n_zip += 1
        for c, d in labels.items():
            if _std(c):
                for role, text in d.items():
                    names[c].setdefault(role, text)
        for link in tree.iter(_L + "presentationLink"):
            loc = {x.get(_X + "label"): x.get(_X + "href").split("#")[-1] for x in link.iter(_L + "loc")}
            kids = defaultdict(list)
            for a in link.iter(_L + "presentationArc"):
                kids[loc[a.get(_X + "from")]].append((loc[a.get(_X + "to")], (a.get("preferredLabel") or "").split("/")[-1]))

            def below(node, seen=()):
                for k, _ in kids.get(node, ()):
                    if k not in seen:
                        yield k
                        yield from below(k, seen + (k,))

            axes = [n for n in kids if n.endswith("Axis") and not n.endswith(_SCOPE_AX)]
            for parent, ks in kids.items():
                starts = {k for k, role in ks if role == "periodStartLabel"}
                for b in starts & {k for k, role in ks if role == "periodEndLabel"}:
                    if not _std(b):
                        continue
                    e = acc[b]
                    e["n"] += 1
                    for k, role in ks:
                        if k != b and _std(k) and not k.endswith("Abstract"):
                            e["items"][k] += 1
                            e["neg"][k] += role.startswith("negated")      # negatedTerseLabel 등 포함
                    for ax in axes:
                        for m in below(ax):
                            if m.endswith("Member") and _std(m):
                                e["measures" if ax.endswith(_MEASURE_AX) else "members"][m] += 1

    def name(c, role="label"):
        return names.get(c, {}).get(role, "")

    out = {"source_zips": n_zip, "accounts": {}}
    for b, e in sorted(acc.items(), key=lambda x: -x[1]["n"]):
        if e["n"] < 2 or not name(b):
            continue
        out["accounts"][b] = {
            "n": e["n"], "label": name(b), "start": name(b, "periodStartLabel"), "end": name(b, "periodEndLabel"),
            # 개념 → [한글 이름표, 쓰인 공시 수, 감소(negatedLabel)로 쓰인 공시 수]
            "items": {c: [name(c), n, e["neg"][c]] for c, n in e["items"].most_common() if n >= 2 and name(c) and c not in acc},
            "members": {c: [name(c), n] for c, n in e["members"].most_common() if n >= 2 and name(c)},
            "measures": {c: [name(c), n] for c, n in e["measures"].most_common() if n >= 2 and name(c)},
        }
    return out


@lru_cache(maxsize=None)
def load(path: str | Path = PATH) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    vocab = build(sys.argv[1])
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else PATH
    out.write_text(json.dumps(vocab, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"zip {vocab['source_zips']}개 → 계정 {len(vocab['accounts'])}개, {out} ({out.stat().st_size / 1e6:.1f}MB)")
