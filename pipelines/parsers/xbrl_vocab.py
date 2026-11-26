"""증감표 후보 목록: DART XBRL 파일의 표시 구조와 한글 이름표에서 모은 계정·항목·자산 종류.

손으로 쓴 단어 목록 대신 쓴다. 증감 묶음은 facts.extract_xbrl과 같은 기준(facts._x_tables)으로 찾는다:
계정 = 같은 부모 아래 '기초 ○○'(periodStartLabel)와 '기말 ○○'(periodEndLabel)이 붙은 개념,
증감 항목 = 표시 순서로 기초~기말 사이의 자식(negated… 역할이면 감소), 같은 표시 묶음의 축 구성요소 = 자산 종류·측정 구분.
회사가 만든 개념(entity…)은 넣지 않는다.

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

_MEASURE_AX = "CarryingAmountAccumulatedDepreciationAmortisationAndImpairmentAndGrossCarryingAmountAxis"


def _std(concept: str) -> bool:
    return not concept.startswith("entity")


def build(zip_dir: str | Path) -> dict:
    """zip 폴더 → 후보 목록. 개념마다 몇 개 공시에서 그 계정의 증감 항목·자산 종류로 쓰였는지 센다."""
    from .facts import _x_tables, _SCOPE_AX
    from .xbrl_reader import _XbrlDoc, _local
    acc = defaultdict(lambda: {"n": 0, "items": Counter(), "neg": Counter(), "members": Counter(), "measures": Counter()})
    names: dict[str, dict[str, str]] = defaultdict(dict)
    n_zip = 0
    for z in sorted(Path(zip_dir).rglob("*.zip")):
        try:
            with zipfile.ZipFile(z) as f:
                files = f.namelist()
                pre = next((n for n in files if n.endswith("_pre.xml")), None)
                lab = next((n for n in files if n.endswith("lab-ko.xml")), None)
                cal = next((n for n in files if n.endswith("_cal.xml")), None)
                if not pre or not lab:
                    continue
                links = _XbrlDoc._presentation(f.read(pre))
                labels = _XbrlDoc._labels(f.read(lab))
                calc = _XbrlDoc._calculation(f.read(cal)) if cal else {}
        except (zipfile.BadZipFile, OSError):
            continue
        n_zip += 1
        for c, d in labels.items():
            if _std(c):
                for role, text in d.items():
                    names[c].setdefault(role, text)
        for _, kids in links:
            for tb in _x_tables(kids, calc):
                b = tb["account"]
                if not _std(b):
                    continue
                e = acc[b]
                e["n"] += 1
                for c, neg, sub, _ in tb["items"]:
                    if not sub and _std(c) and not c.endswith("Abstract"):
                        e["items"][c] += 1
                        e["neg"][c] += neg
                for ax in kids:
                    if ax.endswith("Axis") and _local(ax) != _SCOPE_AX:
                        stack = [k for k, _ in kids.get(ax, ())]
                        seen = set()
                        while stack:
                            m = stack.pop()
                            if m in seen:
                                continue
                            seen.add(m)
                            stack.extend(k for k, _ in kids.get(m, ()))
                            if m.endswith("Member") and _std(m):
                                e["measures" if _local(ax) == _MEASURE_AX else "members"][m] += 1

    def name(c, role="label"):
        return names.get(c, {}).get(role, "")

    out = {"source_zips": n_zip, "accounts": {}}
    for b, e in sorted(acc.items(), key=lambda x: -x[1]["n"]):
        if e["n"] < 2 or not name(b):
            continue
        out["accounts"][b] = {
            "n": e["n"], "label": name(b), "start": name(b, "periodStartLabel"), "end": name(b, "periodEndLabel"),
            # 개념 → [한글 이름표, 쓰인 공시 수, 감소(negated…)로 쓰인 공시 수]
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
