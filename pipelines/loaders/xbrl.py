"""정기공시 XBRL 재무제표 → DuckDB 원본 적재 + annotation.

    python -m loaders.xbrl <새 DuckDB 경로> <XBRL zip 또는 폴더>... [--taxonomy <택소노미 폴더>]

원본 층(값·이름을 바꾸지 않음): filing, context, context_dim, unit, fact_numeric, fact_text, label,
presentation, calculation, definition, role(묶음 주소·이름). annotation 층(원본에서 계산, 다시 만들 수 있음): fact_annotation.
fact_annotation의 role_uri·display_order·member_order는 공시에 실린 순서(표시 구조)대로 정렬하는 열이다:
    ORDER BY display_order, member_order   -- 재무제표·주석 묶음 순서 → 묶음 안 계정 순서 → 구성요소 순서
사실이 실린 묶음의 이름(예: '[D818000] 33. 특수관계자와의 거래')은 role_uri로 role 테이블을 이어 본다.
member_order는 그 묶음에 있는 축마다(묶음 안 순서대로) 사실의 구성요소 자리. 축이 없는 사실은 그 축의 기본값(정의 구조)으로 본다.

회사가 만든 개념('entity<고유번호>_…')은 표준 키가 없어 찾기용 후보를 붙인다(판정이 아니라 검색 보조):
  fact_annotation.std_parent  그 사실이 실린 묶음의 표시 구조에서 가장 가까운 표준 개념 조상
  concept_match               회사 개념 → 표준 개념 후보. relation = same(같은 뜻) / narrower(그 표준 개념의 하위 내역)
    same      same_company_value  같은 회사의 다른 공시가 같은 숫자(같은 기간·축·값, |값| ≥ 1억)를 표준 개념으로 태깅.
                                  숫자 2개 이상이 한 표준 개념과만 겹칠 때. score = 겹친 숫자 수
              concept_id          회사 개념 ID의 기준 이름('<기준>Of<표>…'의 앞부분)이 표준 개념 이름과 같음
              shown_vote          다른 회사들이 표준 개념을 표시한 이름 중 임베딩 점수 ≥ 0.9인 것들을 표준 개념별
                                  회사 수로 투표, 1위가 몫 ≥ 0.7·2개사 이상. 회사가 해마다 쓴 이름표 모두가 같은
                                  개념을 뽑을 때만. 주석(D8…) 묶음의 회사 개념은 같은 주석 묶음 코드의 표시만 센다.
                                  score = 최소 지지 회사 수, model = PARSER_EMBED_MODEL
    narrower  parent              std_parent가 값 개념
              parent_in_id        표시 구조가 없는 'udf_<표>_<시각>_<부모>' 개념의 이름에 적힌 부모
    후보는 회사 개념과 기간 종류·값 형식이 같은 표준 개념만.
개념·구성요소 이름은 'ifrs-full_PropertyPlantAndEquipment' 형식(표시·계산·정의 구조·이름표와 같은 형식).

정정공시: 같은 회사·같은 보고서 종류·같은 기간 말의 zip이 여럿이면 접수번호가 가장 늦은 것(최종 정정본)만 적재하고,
교체된 앞 접수번호는 filing.replaces에 남긴다. 정정공시를 새로 받으면 다시 적재할 때 자동으로 바뀐다.
기존 파일은 건드리지 않는다: '<경로>.tmp'에 만들고 검증을 통과하면 그 경로로 바꾼다.
zip은 OpenDART 재무제표 원본파일(XBRL) API로 받은 것(이름: 접수번호_보고서코드.zip).
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
from collections import defaultdict
from datetime import datetime
from pathlib import Path

import numpy as np

from parsers import PARSER_VERSION
from parsers.facts import _precision, _x_period
from parsers.xbrl_reader import _PRIVATE_PREFIX, filing_key, read_taxonomy, read_xbrl

LOADER_VERSION = f"xbrl-1.0/{PARSER_VERSION}"
_SCOPE_AX = "ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis"
_SCOPE_OF = {"ifrs-full_ConsolidatedMember": "consolidated", "ifrs-full_SeparateMember": "separate"}
_UNIT_KO = {"iso4217:KRW": "원", "xbrli:shares": "주", "xbrli:pure": ""}
# 표시 위치에 붙는 이름표 역할(기초·기말·합계). 표시 구조에서 쓰일 때만 그 개념의 이름이다 — 안 쓰인 것은
# 작성 도구가 자동으로 채운 자리표시('기초 새 아이템 (2)')인 경우가 많다
_POSITION_ROLES = {"periodStartLabel", "periodEndLabel", "totalLabel", "negatedTotalLabel",
                   "negatedPeriodStartLabel", "negatedPeriodEndLabel"}
_MATCH_MIN_VALUE = 1e8     # 원. 같은 숫자로 잇기: 이보다 작은 값은 우연히 같을 수 있어 쓰지 않는다
_MATCH_MIN_FACTS = 2       # 같은 숫자로 잇기: 겹친 숫자가 이만큼은 있어야
TAXONOMY_DIR = Path(__file__).resolve().parents[2] / "data" / "raw" / "dart_taxonomy"
_MATCH_BATCH = 500        # 임베딩 점수 행렬을 이만큼씩 나눠 계산(이름이 수만 개라 메모리)
_VOTE_MIN_SCORE = 0.9     # 다른 회사 투표: 이름 점수
_VOTE_MIN_SHARE = 0.7     # 다른 회사 투표: 가장 많은 표준 개념의 몫
_VOTE_MIN_CORPS = 2       # 다른 회사 투표: 지지 회사 수
_strip = lambda t: re.sub(r"\s*\[\s*(구성요소|member|개요|항목|문장영역)\s*\]\s*$", "", t or "", flags=re.I).strip()


SCHEMA = """
CREATE TABLE filing (
  rcept_no VARCHAR PRIMARY KEY, corp_code VARCHAR NOT NULL, report_code VARCHAR NOT NULL,
  rcept_date DATE NOT NULL, period_end DATE, source_zip VARCHAR NOT NULL, source_sha256 VARCHAR NOT NULL,
  loaded_at TIMESTAMP NOT NULL, loader_version VARCHAR NOT NULL, replaces VARCHAR[]);
CREATE TABLE context (
  rcept_no VARCHAR, context_id VARCHAR, period_type VARCHAR, start_date DATE, end_date DATE,
  PRIMARY KEY (rcept_no, context_id));
CREATE TABLE context_dim (
  rcept_no VARCHAR, context_id VARCHAR, axis VARCHAR, member VARCHAR, PRIMARY KEY (rcept_no, context_id, axis));
CREATE TABLE unit (
  rcept_no VARCHAR, unit_id VARCHAR, numerator VARCHAR, denominator VARCHAR, PRIMARY KEY (rcept_no, unit_id));
CREATE TABLE fact_numeric (
  rcept_no VARCHAR, seq INTEGER, concept VARCHAR NOT NULL, context_id VARCHAR NOT NULL, unit_id VARCHAR NOT NULL,
  decimals VARCHAR, value_text VARCHAR NOT NULL, value DECIMAL(38, 6), PRIMARY KEY (rcept_no, seq));
CREATE TABLE fact_text (
  rcept_no VARCHAR, seq INTEGER, concept VARCHAR NOT NULL, context_id VARCHAR NOT NULL, lang VARCHAR,
  value_text VARCHAR NOT NULL, PRIMARY KEY (rcept_no, seq));
CREATE TABLE label (
  rcept_no VARCHAR, concept VARCHAR, lang VARCHAR, role VARCHAR, text VARCHAR, PRIMARY KEY (rcept_no, concept, lang, role));
CREATE TABLE presentation (
  rcept_no VARCHAR, role_uri VARCHAR, parent VARCHAR, child VARCHAR, ord DOUBLE, preferred_label VARCHAR);
CREATE TABLE calculation (
  rcept_no VARCHAR, role_uri VARCHAR, parent VARCHAR, child VARCHAR, ord DOUBLE, weight DOUBLE);
CREATE TABLE definition (
  rcept_no VARCHAR, role_uri VARCHAR, src VARCHAR, dst VARCHAR, arcrole VARCHAR, ord DOUBLE);
CREATE TABLE role (
  rcept_no VARCHAR, role_uri VARCHAR, definition VARCHAR, PRIMARY KEY (rcept_no, role_uri));
CREATE TABLE fact_annotation (
  rcept_no VARCHAR, seq INTEGER, scope VARCHAR, period_label VARCHAR, precision VARCHAR, concept_label VARCHAR,
  company_labels VARCHAR[], member_labels VARCHAR[], candidate_names VARCHAR[],
  role_uri VARCHAR, display_order INTEGER, member_order INTEGER[], std_parent VARCHAR, PRIMARY KEY (rcept_no, seq));
CREATE TABLE concept_match (
  concept VARCHAR, relation VARCHAR, match_type VARCHAR, match_concept VARCHAR, match_label VARCHAR, score DOUBLE,
  query VARCHAR, model VARCHAR, PRIMARY KEY (concept, match_type));
"""


def _zips(args: list[str]) -> list[Path]:
    out = []
    for a in args:
        p = Path(a)
        out += sorted(p.rglob("*.zip")) if p.is_dir() else [p]
    return list(dict.fromkeys(p.resolve() for p in out))


def _display_orders(presentation: list[dict]) -> list[tuple[str, dict[str, int], dict[str, str]]]:
    """표시 구조 → [(묶음 role, 개념·구성요소 → 묶음 안 순서, 개념 → 부모)], 묶음은 파일에 나온 순서.
    순서는 연결선의 order대로 위에서부터 깊이 우선으로 처음 나온 자리."""
    kids: dict[str, dict[str, list]] = {}
    parent: dict[str, dict[str, str]] = {}
    for a in presentation:
        kids.setdefault(a["role_uri"], {}).setdefault(a["src"], []).append((a["ord"] or 0, a["dst"]))
        parent.setdefault(a["role_uri"], {}).setdefault(a["dst"], a["src"])
    out = []
    for role, k in kids.items():
        children = {c for v in k.values() for _, c in v}
        pos: dict[str, int] = {}
        stack = [r for r in reversed(list(k)) if r not in children]
        while stack:
            c = stack.pop()
            if c in pos:
                continue
            pos[c] = len(pos)
            stack += [d for _, d in sorted(k.get(c, []), key=lambda t: t[0], reverse=True)]
        out.append((role, pos, parent.get(role, {})))
    return out


def _annotations(rcept_no: str, x: dict, period_end: str, std_ko: dict[str, str]) -> list[tuple]:
    """사실(숫자·텍스트)마다 연결/별도·기간 구분·반올림 단위·이름표·후보 계정명·표시 순서·표준 조상.
    표시 묶음은 그 개념이 있는 묶음 중 사실의 구성요소(연결/별도 포함)가 가장 많이 들어 있는 것, 같으면 앞 묶음.
    std_ko: 택소노미 표준 한글 이름표(공시 zip에 표준 이름표가 없을 때)."""
    ko: dict[str, dict[str, str]] = {}
    for lb in x["labels"]:
        if lb["lang"] == "ko":
            ko.setdefault(lb["concept"], {})[lb["role"]] = lb["text"]
    ctx = {c["context_id"]: c for c in x["contexts"]}
    dims: dict[str, dict[str, str]] = {}
    for d in x["context_dims"]:
        dims.setdefault(d["context_id"], {})[d["axis"]] = d["member"]
    units = {u["unit_id"]: u for u in x["units"]}
    roles = _display_orders(x["presentation"])
    used = {(a["dst"], a["preferred_label"].split("/")[-1]) for a in x["presentation"] if a["preferred_label"]}
    own = f"entity{x['entity']}_"
    defaults = {a["src"]: a["dst"] for a in x["definition"] if a["arcrole"].endswith("/dimension-default")}
    base, n = [], 0                  # 묶음마다 앞 묶음들의 자리 수를 더해 공시 전체에서 하나의 순서로
    for _, pos, _ in roles:
        base.append(n)
        n += len(pos)
    out = []
    for f in x["facts_numeric"] + x["facts_text"]:
        c, dm = ctx.get(f["context_id"], {}), dims.get(f["context_id"], {})
        lab = ko.get(f["concept"], {})
        std = _strip(lab.get("label", "") or std_ko.get(f["concept"], ""))
        company = [_strip(t) for r, t in lab.items() if r not in ("label", "commentaryGuidance")
                   and (r not in _POSITION_ROLES or (f["concept"], r) in used)]
        members = [_strip(ko.get(m, {}).get("label", m)) for a, m in dm.items() if a != _SCOPE_AX]
        hit = max(((sum(m in pos for m in dm.values()), -i) for i, (_, pos, _) in enumerate(roles) if f["concept"] in pos),
                  default=None)
        role, order, morder, std_parent = None, None, None, None
        if hit:
            i = -hit[1]
            role, pos, par = roles[i]
            if f["concept"].startswith(own):
                up = par.get(f["concept"])
                while up and up.startswith(own):
                    up = par.get(up)
                std_parent = up
            order = base[i] + pos[f["concept"]]
            axes = sorted((pos[a], a) for a in set(dm) | set(defaults) if a in pos and a != _SCOPE_AX)
            morder = [pos.get(dm.get(a, defaults.get(a)), -1) for _, a in axes]
        u = units.get(f.get("unit_id"), {})
        unit = "" if u.get("denominator") else _UNIT_KO.get(u.get("numerator", ""), u.get("numerator", ""))
        out.append((rcept_no, f["seq"], _SCOPE_OF.get(dm.get(_SCOPE_AX, ""), ""),
                    _x_period(c["end_date"], period_end) if c.get("end_date") and period_end else "",
                    _precision(f.get("decimals"), unit) if "unit_id" in f else "",
                    std, list(dict.fromkeys(company)), members, [t for t in dict.fromkeys([std] + company) if t],
                    role, order, morder, std_parent))
    return out


_SAME_VALUE = """
WITH k AS (
  SELECT n.rcept_no, f.corp_code, n.concept, x.start_date, x.end_date, round(n.value) AS v,
         coalesce(string_agg(d.axis || '=' || d.member, ',' ORDER BY d.axis), '') AS dims,
         bool_or(coalesce(d.member, '') LIKE 'entity%') AS company_member
  FROM fact_numeric n JOIN filing f USING (rcept_no) JOIN context x USING (rcept_no, context_id)
  LEFT JOIN context_dim d USING (rcept_no, context_id)
  WHERE abs(n.value) >= ?
  GROUP BY n.rcept_no, n.seq, f.corp_code, n.concept, x.start_date, x.end_date, n.value),
own AS (SELECT * FROM k WHERE NOT company_member AND concept LIKE 'entity' || corp_code || '!_%' ESCAPE '!'),
std AS (SELECT * FROM k WHERE NOT company_member AND concept NOT LIKE 'entity%'),
hit AS (
  SELECT o.concept, o.start_date, o.end_date, o.dims, o.v, list(DISTINCT s.concept) AS stds
  FROM own o JOIN std s ON s.corp_code = o.corp_code AND s.rcept_no <> o.rcept_no AND s.end_date = o.end_date
       AND s.start_date IS NOT DISTINCT FROM o.start_date AND s.dims = o.dims AND s.v = o.v
  GROUP BY ALL),
votes AS (SELECT concept, stds[1] AS std, count(*) AS n FROM hit WHERE len(stds) = 1 GROUP BY ALL)
SELECT concept, any_value(std), sum(n) FROM votes GROUP BY concept HAVING count(*) = 1 AND sum(n) >= ?
"""


_SHOWN = """
SELECT DISTINCT f.corp_code, p.child, coalesce(l.text, l2.text), regexp_extract(p.role_uri, 'role-([A-Z][0-9]{3})', 1)
FROM presentation p JOIN filing f USING (rcept_no)
LEFT JOIN label l ON l.rcept_no = p.rcept_no AND l.concept = p.child AND l.lang = 'ko'
     AND l.role = split_part(p.preferred_label, '/', -1)
LEFT JOIN label l2 ON l2.rcept_no = p.rcept_no AND l2.concept = p.child AND l2.lang = 'ko' AND l2.role = 'label'
WHERE p.child NOT LIKE 'entity%' AND coalesce(l.text, l2.text) IS NOT NULL
"""
_OWN = """
WITH f AS (SELECT rcept_no, seq, concept, context_id, 'number' AS vtype FROM fact_numeric
           UNION ALL SELECT rcept_no, seq, concept, context_id, 'text' FROM fact_text)
SELECT f.concept, any_value(fl.corp_code), list(DISTINCT x.period_type), any_value(f.vtype),
       list(DISTINCT l.text) FILTER (WHERE l.text IS NOT NULL),
       mode(a.std_parent), list(DISTINCT regexp_extract(a.role_uri, 'role-([A-Z][0-9]{3})', 1))
FROM f JOIN filing fl USING (rcept_no) JOIN context x USING (rcept_no, context_id)
JOIN fact_annotation a USING (rcept_no, seq)
LEFT JOIN label l ON l.rcept_no = f.rcept_no AND l.concept = f.concept AND l.lang = 'ko' AND l.role = 'label'
WHERE f.concept LIKE 'entity' || fl.corp_code || '!_%' ESCAPE '!'
GROUP BY f.concept
"""


def _id_parts(concept: str) -> tuple[str | None, str | None]:
    """회사 개념 ID → (기준 이름, 이름에 적힌 부모). DART 작성 도구가 만드는 두 형식:
    '<기준>Of<표 이름>…'(예: FinancialAssetsDenominatedInForeignCurrencyOriginalCurrencyKgsOf…TableOfItems)
    'udf_<BS·IS·CF·CE·NOTE>_<시각>_<부모>'(예: udf_BS_202131017149375_CurrentLiabilities, 부모가 없으면 None)."""
    local = concept.split("_", 1)[1]
    if local.startswith("udf_"):
        m = re.search(r"_\d{8,}_([A-Za-z]+)$", local)
        return None, m.group(1) if m else None
    return re.split(r"Of(?=[A-Z])", local)[0], None


def _concept_match(con, taxonomy: dict) -> None:
    """회사가 만든 개념 → concept_match(모듈 설명)."""
    from parsers.align import MODEL_ENV, _setting, similarity
    own = con.execute(_OWN).fetchall()
    # 표준 개념: 택소노미 + 공시에 나온 표준 개념(다른 버전). (기간 종류, 값 형식)
    std = {k: (v["period"], v["vtype"]) for k, v in taxonomy.items()}
    for k, p, vt in con.execute("""SELECT concept, mode(x.period_type), 'number' FROM fact_numeric
            JOIN context x USING (rcept_no, context_id) WHERE concept NOT LIKE 'entity%' GROUP BY concept""").fetchall():
        std.setdefault(k, (p, vt))
    db_label = dict(con.execute("""SELECT concept, max(text) FROM label WHERE lang = 'ko' AND role = 'label'
                                   AND concept NOT LIKE 'entity%' GROUP BY concept""").fetchall())
    label = lambda k: _strip(db_label.get(k) or taxonomy.get(k, {}).get("labels", {}).get("ko")
                             or taxonomy.get(k, {}).get("labels", {}).get("en") or "")
    by_local: dict[str, str] = {}
    for k in sorted(std, key=lambda k: (not k.startswith("ifrs-full_"), k)):   # 같은 이름이면 IFRS 개념
        by_local.setdefault(k.split("_", 1)[1], k)
    # 기간 종류: 회사가 한 개념을 시점·기간 맥락에 섞어 쓰기도 해서(원본 그대로 적재) 그중 하나라도 맞으면 된다
    fits = lambda k, periods, vtype: k in std and std[k][0] in periods and std[k][1] == vtype

    out = [(o, "same", "same_company_value", s, label(s), float(n), None, None)
           for o, s, n in con.execute(_SAME_VALUE, [_MATCH_MIN_VALUE, _MATCH_MIN_FACTS]).fetchall()]
    for k, corp, periods, vtype, lab, parent, _ in own:
        base, id_parent = _id_parts(k)
        if base and fits(by_local.get(base), periods, vtype):
            out.append((k, "same", "concept_id", by_local[base], label(by_local[base]), None, base, None))
        if parent and fits(parent, periods, vtype):
            out.append((k, "narrower", "parent", parent, label(parent), None, None, None))
        elif not parent and id_parent and fits(by_local.get(id_parent), periods, vtype):
            p = by_local[id_parent]
            out.append((k, "narrower", "parent_in_id", p, label(p), None, id_parent, None))

    # shown_vote: 다른 회사들이 표준 개념을 표시한 이름 → 그 이름을 쓴 회사들
    shown = con.execute(_SHOWN).fetchall()
    names = sorted({t for _, _, t, _ in shown})
    nid = {t: i for i, t in enumerate(names)}
    who: dict[int, dict[str, set]] = defaultdict(lambda: defaultdict(set))   # 이름 → 표준 개념 → (회사, 묶음 코드)
    for corp, k, t, code in shown:
        who[nid[t]][k].add((corp, code))
    # 회사가 해마다 쓴 이름표마다 투표해 모두 같은 표준 개념일 때만 붙인다. 주석(D8…) 묶음의 회사 개념은 같은 주석
    # 묶음 코드에서 표시된 것만 센다 — 특수관계자 거래 주석의 '기타수익'에 손익계산서 '기타이익'이 붙지 않게
    notes = {r[0]: {c for c in r[6] if c and c.startswith("D8")} for r in own}
    kinds = {r[0]: (r[2], r[3]) for r in own}
    ents = [(r[0], r[1], t) for r in own if r[3] == "number" for t in sorted({_strip(t) for t in r[4]} - {""})]
    model = _setting(MODEL_ENV)
    slots = [{"path": [_strip(t)], "name": _strip(t)} for t in names]
    picked: dict[str, set] = defaultdict(set)
    support: dict[str, int] = {}
    for b in range(0, len(ents) if slots else 0, _MATCH_BATCH):
        batch = ents[b:b + _MATCH_BATCH]
        sim = similarity([{"path": [t], "name": t} for *_, t in batch], slots)
        for i, (k, corp, _) in enumerate(batch):
            votes: dict[str, set] = defaultdict(set)
            for j in np.nonzero(sim[i] >= _VOTE_MIN_SCORE)[0]:
                for s, cs in who[j].items():
                    votes[s] |= {c for c, code in cs if c != corp and (not notes[k] or code in notes[k])}
            votes = {s: v for s, v in votes.items() if v and fits(s, *kinds[k])}   # 구성요소·형식 다른 개념 제외
            s, v = max(votes.items(), key=lambda t: (len(t[1]), t[0])) if votes else (None, set())
            ok = len(v) >= _VOTE_MIN_CORPS and len(v) / sum(len(x) for x in votes.values()) >= _VOTE_MIN_SHARE
            picked[k].add(s if ok else None)
            support[k] = min(support.get(k, len(v)), len(v))
    queries = defaultdict(list)
    for k, _, t in ents:
        queries[k].append(t)
    for k, got in picked.items():
        if len(got) == 1 and None not in got:
            s = next(iter(got))
            out.append((k, "same", "shown_vote", s, label(s), float(support[k]), " / ".join(queries[k]), model))
    _insert(con, "concept_match", out)


def _fill_labels(con) -> None:
    """택소노미에도 한글 이름표가 없는 표준 개념(새 IFRS 개념 등) 사실 → 다른 공시가 그 개념에 단 표준 한글 이름표."""
    rows = con.execute("""
      WITH f AS (SELECT rcept_no, seq, concept FROM fact_numeric UNION ALL SELECT rcept_no, seq, concept FROM fact_text),
      l AS (SELECT concept, max(text) AS text FROM label WHERE lang = 'ko' AND role = 'label' GROUP BY concept)
      SELECT a.rcept_no, a.seq, l.text, a.candidate_names FROM fact_annotation a JOIN f USING (rcept_no, seq)
      JOIN l USING (concept) WHERE coalesce(a.concept_label, '') = ''""").fetchall()
    con.executemany("UPDATE fact_annotation SET concept_label = ?, candidate_names = ? WHERE rcept_no = ? AND seq = ?",
                    [(_strip(t), list(dict.fromkeys([_strip(t)] + (names or []))), r, s) for r, s, t, names in rows])


def _insert(con, table: str, rows: list[tuple]) -> None:
    if rows:
        con.executemany(f"INSERT INTO {table} VALUES ({', '.join('?' * len(rows[0]))})", rows)


def load(out: str | Path, zips: list[Path], taxonomy_dir: str | Path = TAXONOMY_DIR) -> dict:
    """zip들 → 새 DuckDB 파일. 검증을 통과해야 out 경로로 바꾼다.
    taxonomy_dir: collectors/dart_xbrl.py가 받은 DART 택소노미 폴더. 반환: {"filings", "replaced", "skipped_private", "path"}."""
    import duckdb
    taxonomy = read_taxonomy(taxonomy_dir) if Path(taxonomy_dir).is_dir() else {}
    if not taxonomy:
        raise RuntimeError(f"택소노미가 없습니다: {taxonomy_dir} — python -m collectors.dart_xbrl로 받으세요")
    std_ko = {k: v["labels"]["ko"] for k, v in taxonomy.items() if v["labels"].get("ko")}
    out = Path(out)
    tmp = out.with_name(out.name + ".tmp")
    tmp.unlink(missing_ok=True)
    con = duckdb.connect(str(tmp))
    con.execute(SCHEMA)
    expected, skipped = {}, 0
    latest: dict[tuple, dict] = {}             # (회사, 기간 말, 보고서코드) → {접수번호: zip} — 정정본 교체
    for z in zips:
        m = re.match(r"(\d{14})_(\d{5})\.zip$", z.name)
        if m:                                  # 이름이 접수번호_보고서코드.zip이 아닌 파일은 건너뛴다
            latest.setdefault((*filing_key(z), m.group(2)), {})[m.group(1)] = z
    for (_, period_end, report_code), group in latest.items():
        rcept_no, *older = sorted(group, reverse=True)
        z, replaces = group[rcept_no], sorted(older)
        x = read_xbrl(z)
        _insert(con, "filing", [(rcept_no, x["entity"], report_code, datetime.strptime(rcept_no[:8], "%Y%m%d").date(),
                                 period_end, z.name, hashlib.sha256(z.read_bytes()).hexdigest(), datetime.now(),
                                 LOADER_VERSION, replaces)])
        _insert(con, "context", [(rcept_no, c["context_id"], c["period_type"], c["start_date"], c["end_date"])
                                 for c in x["contexts"]])
        _insert(con, "context_dim", [(rcept_no, d["context_id"], d["axis"], d["member"]) for d in x["context_dims"]])
        _insert(con, "unit", [(rcept_no, u["unit_id"], u["numerator"], u["denominator"]) for u in x["units"]])
        _insert(con, "fact_numeric", [(rcept_no, f["seq"], f["concept"], f["context_id"], f["unit_id"], f["decimals"],
                                       f["value_text"], f["value"]) for f in x["facts_numeric"]])
        _insert(con, "fact_text", [(rcept_no, f["seq"], f["concept"], f["context_id"], f["lang"], f["value_text"])
                                   for f in x["facts_text"]])
        _insert(con, "label", [(rcept_no, lb["concept"], lb["lang"], lb["role"], lb["text"]) for lb in x["labels"]])
        _insert(con, "presentation", [(rcept_no, a["role_uri"], a["src"], a["dst"], a["ord"], a["preferred_label"])
                                      for a in x["presentation"]])
        _insert(con, "calculation", [(rcept_no, a["role_uri"], a["src"], a["dst"], a["ord"], a["weight"])
                                     for a in x["calculation"]])
        _insert(con, "definition", [(rcept_no, a["role_uri"], a["src"], a["dst"], a["arcrole"], a["ord"])
                                    for a in x["definition"]])
        _insert(con, "role", list({(rcept_no, r["role_uri"]): (rcept_no, r["role_uri"], r["definition"])
                                   for r in x["roles"]}.values()))
        _insert(con, "fact_annotation", _annotations(rcept_no, x, period_end, std_ko))
        expected[rcept_no] = (len(x["facts_numeric"]), len(x["facts_text"]))
        skipped += x["skipped"]
    _fill_labels(con)
    _concept_match(con, taxonomy)
    problems = _validate(con, expected)
    con.close()
    if problems:
        raise RuntimeError("검증 실패 — 기존 파일은 그대로 둔다:\n" + "\n".join(problems[:20]))
    os.replace(tmp, out)
    return {"filings": len(expected), "replaced": sum(len(g) - 1 for g in latest.values()),
            "skipped_private": skipped, "path": str(out)}


def _validate(con, expected: dict) -> list[str]:
    """적재한 값이 읽은 원본과 개수가 같고, 사실마다 맥락·단위·annotation이 있고, 개인정보 개념이 없는지."""
    q = lambda sql: con.execute(sql).fetchall()
    problems = []
    got = {r: (n, t) for r, n, t in q("""SELECT f.rcept_no,
        (SELECT count(*) FROM fact_numeric n WHERE n.rcept_no = f.rcept_no),
        (SELECT count(*) FROM fact_text t WHERE t.rcept_no = f.rcept_no) FROM filing f""")}
    problems += [f"{r}: 사실 개수 {got.get(r)} ≠ 원본 {e}" for r, e in expected.items() if got.get(r) != e]
    checks = {
        "맥락 없는 숫자 사실": "SELECT count(*) FROM fact_numeric f ANTI JOIN context c USING (rcept_no, context_id)",
        "맥락 없는 텍스트 사실": "SELECT count(*) FROM fact_text f ANTI JOIN context c USING (rcept_no, context_id)",
        "단위 없는 숫자 사실": "SELECT count(*) FROM fact_numeric f ANTI JOIN unit u USING (rcept_no, unit_id)",
        "annotation 없는 사실": """SELECT (SELECT count(*) FROM fact_numeric) + (SELECT count(*) FROM fact_text)
                                    - (SELECT count(*) FROM fact_annotation)""",
        "이름 없는 표준 개념 사실": """SELECT count(*) FROM fact_numeric n JOIN fact_annotation a USING (rcept_no, seq)
            WHERE n.concept NOT LIKE 'entity%' AND len(a.candidate_names) = 0""",
        "숫자로 못 읽은 숫자 사실": "SELECT count(*) FROM fact_numeric WHERE value IS NULL AND trim(value_text) <> ''",
        "표준 개념이 아닌 후보": "SELECT count(*) FROM concept_match WHERE match_concept LIKE 'entity%'",
        "개인정보 개념": " UNION ALL ".join(f"SELECT count(*) FROM fact_text WHERE concept LIKE '{p}%'"
                                         for p in _PRIVATE_PREFIX),
    }
    for name, sql in checks.items():
        n = sum(r[0] for r in q(sql))
        if n:
            problems.append(f"{name}: {n}")
    return problems


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out", help="새 DuckDB 경로")
    ap.add_argument("zips", nargs="+", help="XBRL zip 또는 폴더")
    ap.add_argument("--taxonomy", default=TAXONOMY_DIR, help=f"택소노미 폴더 (기본: {TAXONOMY_DIR})")
    a = ap.parse_args()
    print(load(a.out, _zips(a.zips), a.taxonomy))
