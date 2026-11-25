"""정기공시 XBRL 재무제표 → DuckDB 원본 적재 + annotation.

    python -m loaders.xbrl <새 DuckDB 경로> <XBRL zip 또는 폴더>...

원본 층(값·이름을 바꾸지 않음): filing, context, context_dim, unit, fact_numeric, fact_text, label,
presentation, calculation, definition. annotation 층(원본에서 계산, 다시 만들 수 있음): fact_annotation.
개념·구성요소 이름은 'ifrs-full_PropertyPlantAndEquipment' 형식(표시·계산·정의 구조·이름표와 같은 형식).

기존 파일은 건드리지 않는다: '<경로>.tmp'에 만들고 검증을 통과하면 그 경로로 바꾼다.
zip은 OpenDART 재무제표 원본파일(XBRL) API로 받은 것(이름: 접수번호_보고서코드.zip).
"""
from __future__ import annotations

import hashlib
import os
import re
import sys
from datetime import datetime
from pathlib import Path

from parsers import PARSER_VERSION
from parsers.facts import _precision, _x_period
from parsers.xbrl_reader import _PRIVATE_PREFIX, read_xbrl

LOADER_VERSION = f"xbrl-1.0/{PARSER_VERSION}"
_SCOPE_AX = "ifrs-full_ConsolidatedAndSeparateFinancialStatementsAxis"
_SCOPE_OF = {"ifrs-full_ConsolidatedMember": "consolidated", "ifrs-full_SeparateMember": "separate"}
_UNIT_KO = {"iso4217:KRW": "원", "xbrli:shares": "주", "xbrli:pure": ""}

SCHEMA = """
CREATE TABLE filing (
  rcept_no VARCHAR PRIMARY KEY, corp_code VARCHAR NOT NULL, report_code VARCHAR NOT NULL,
  rcept_date DATE NOT NULL, period_end DATE, source_zip VARCHAR NOT NULL, source_sha256 VARCHAR NOT NULL,
  loaded_at TIMESTAMP NOT NULL, loader_version VARCHAR NOT NULL);
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
CREATE TABLE fact_annotation (
  rcept_no VARCHAR, seq INTEGER, scope VARCHAR, period_label VARCHAR, precision VARCHAR, concept_label VARCHAR,
  company_labels VARCHAR[], member_labels VARCHAR[], candidate_names VARCHAR[], PRIMARY KEY (rcept_no, seq));
"""


def _zips(args: list[str]) -> list[Path]:
    out = []
    for a in args:
        p = Path(a)
        out += sorted(p.rglob("*.zip")) if p.is_dir() else [p]
    return out


def _annotations(rcept_no: str, x: dict, period_end: str) -> list[tuple]:
    """사실(숫자·텍스트)마다 연결/별도·기간 구분·반올림 단위·이름표·후보 계정명."""
    ko: dict[str, dict[str, str]] = {}
    for lb in x["labels"]:
        if lb["lang"] == "ko":
            ko.setdefault(lb["concept"], {})[lb["role"]] = lb["text"]
    strip = lambda t: re.sub(r"\s*\[(구성요소|member|개요)\]\s*$", "", t or "", flags=re.I)
    ctx = {c["context_id"]: c for c in x["contexts"]}
    dims: dict[str, dict[str, str]] = {}
    for d in x["context_dims"]:
        dims.setdefault(d["context_id"], {})[d["axis"]] = d["member"]
    units = {u["unit_id"]: u for u in x["units"]}
    out = []
    for f in x["facts_numeric"] + x["facts_text"]:
        c, dm = ctx.get(f["context_id"], {}), dims.get(f["context_id"], {})
        roles = ko.get(f["concept"], {})
        std = strip(roles.get("label", ""))
        company = [strip(t) for r, t in roles.items() if r not in ("label", "commentaryGuidance")]
        members = [strip(ko.get(m, {}).get("label", m)) for a, m in dm.items() if a != _SCOPE_AX]
        u = units.get(f.get("unit_id"), {})
        unit = "" if u.get("denominator") else _UNIT_KO.get(u.get("numerator", ""), u.get("numerator", ""))
        out.append((rcept_no, f["seq"], _SCOPE_OF.get(dm.get(_SCOPE_AX, ""), ""),
                    _x_period(c["end_date"], period_end) if c.get("end_date") and period_end else "",
                    _precision(f.get("decimals"), unit) if "unit_id" in f else "",
                    std, list(dict.fromkeys(company)), members, [t for t in dict.fromkeys([std] + company) if t]))
    return out


def _insert(con, table: str, rows: list[tuple]) -> None:
    if rows:
        con.executemany(f"INSERT INTO {table} VALUES ({', '.join('?' * len(rows[0]))})", rows)


def load(out: str | Path, zips: list[Path]) -> dict:
    """zip들 → 새 DuckDB 파일. 검증을 통과해야 out 경로로 바꾼다. 반환: {"filings", "skipped_private", ...}."""
    import duckdb
    out = Path(out)
    tmp = out.with_name(out.name + ".tmp")
    tmp.unlink(missing_ok=True)
    con = duckdb.connect(str(tmp))
    con.execute(SCHEMA)
    expected, seen, skipped = {}, set(), 0
    for z in zips:
        m = re.match(r"(\d{14})_(\d{5})\.zip$", z.name)
        if not m or m.group(1) in seen:
            continue           # 이름이 접수번호_보고서코드.zip이 아니거나 이미 넣은 공시
        rcept_no, report_code = m.groups()
        seen.add(rcept_no)
        x = read_xbrl(z)
        ends = [c["end_date"] for c in x["contexts"] if c["period_type"] == "duration" and c["end_date"]]
        period_end = max(ends) if ends else None
        _insert(con, "filing", [(rcept_no, x["entity"], report_code, datetime.strptime(rcept_no[:8], "%Y%m%d").date(),
                                 period_end, z.name, hashlib.sha256(z.read_bytes()).hexdigest(), datetime.now(),
                                 LOADER_VERSION)])
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
        _insert(con, "fact_annotation", _annotations(rcept_no, x, period_end))
        expected[rcept_no] = (len(x["facts_numeric"]), len(x["facts_text"]))
        skipped += x["skipped"]
    problems = _validate(con, expected)
    con.close()
    if problems:
        raise RuntimeError("검증 실패 — 기존 파일은 그대로 둔다:\n" + "\n".join(problems[:20]))
    os.replace(tmp, out)
    return {"filings": len(expected), "skipped_private": skipped, "path": str(out)}


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
        "숫자로 못 읽은 숫자 사실": "SELECT count(*) FROM fact_numeric WHERE value IS NULL AND trim(value_text) <> ''",
        "개인정보 개념": " UNION ALL ".join(f"SELECT count(*) FROM fact_text WHERE concept LIKE '{p}%'"
                                         for p in _PRIVATE_PREFIX),
    }
    for name, sql in checks.items():
        n = sum(r[0] for r in q(sql))
        if n:
            problems.append(f"{name}: {n}")
    return problems


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    print(load(sys.argv[1], _zips(sys.argv[2:])))
