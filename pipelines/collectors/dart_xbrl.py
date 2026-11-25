"""OpenDART 정기공시(사업·반기·분기보고서) XBRL 원본 zip 수집.

받은 바이트를 그대로 저장한다. 파싱·적재는 하지 않는다 (parsers/xbrl_reader.py, loaders/xbrl.py 담당).

저장 위치: data/raw/dart_xbrl/<고유번호>/<접수번호>_<보고서코드>.zip   (loaders/xbrl.py가 읽는 이름)
    보고서코드: 11011 사업 / 11012 반기 / 11013 1분기 / 11014 3분기
    정정 공시는 최종본만 받는다(last_reprt_at=Y). 이미 받은 파일은 다시 받지 않는다.

실행 (pipelines 폴더에서): python -m collectors.dart_xbrl <고유번호> ... [--from YYYYMMDD] [--to YYYYMMDD]
API 키: 환경변수 또는 저장소 루트 .env의 OPENDART_API_KEY
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

SOURCE = "dart_xbrl"
BASE_URL = "https://opendart.fss.or.kr/api"
ROOT = Path(__file__).resolve().parents[2]
RAW_DIR = ROOT / "data" / "raw"
SLEEP_SECONDS = 0.5        # 요청 사이 간격 (OpenDART 분당 호출 한도)

# 보고서 이름: "[기재정정]분기보고서 (2025.09)". 제출기한연장신고서 등 다른 공시는 맞지 않는다
_REPORT_NM = re.compile(r"^(\[[^\]]+\])?(사업|반기|분기)보고서 \((\d{4})\.(\d{2})\)$")
_CODE_OF_KIND = {"사업": "11011", "반기": "11012"}
# 분기보고서는 결산월에서 몇 달 뒤 기간 말인지로 1분기·3분기를 가른다 (결산월이 12월이 아닌 회사도 같은 규칙)
_QUARTER_BY_OFFSET = {3: "11013", 9: "11014"}


class DartError(RuntimeError):
    def __init__(self, status: str, message: str):
        super().__init__(f"OpenDART {status}: {message}")
        self.status = status


def _api_key() -> str:
    if os.environ.get("OPENDART_API_KEY"):
        return os.environ["OPENDART_API_KEY"]
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.strip().startswith("OPENDART_API_KEY="):
                return line.split("=", 1)[1].strip().strip("\"'")
    raise SystemExit("OPENDART_API_KEY가 없습니다 (환경변수 또는 .env)")


def _get(endpoint: str, params: dict, key: str) -> bytes:
    url = f"{BASE_URL}/{endpoint}?" + urllib.parse.urlencode({"crtfc_key": key, **params})
    with urllib.request.urlopen(url, timeout=60) as r:
        raw = r.read()
    time.sleep(SLEEP_SECONDS)
    return raw


def _json(endpoint: str, params: dict, key: str) -> dict:
    j = json.loads(_get(endpoint, params, key))
    if j.get("status") not in ("000", "013"):          # 013: 조회된 데이터 없음
        raise DartError(j.get("status", ""), j.get("message", ""))
    return j


def report_code(report_nm: str, acc_mt: str) -> str | None:
    """정기공시 이름 + 결산월 → 보고서코드. 정기공시가 아니거나 분기 기간이 결산월과 안 맞으면 None."""
    m = _REPORT_NM.match(report_nm.strip())
    if not m:
        return None
    if m.group(2) in _CODE_OF_KIND:
        return _CODE_OF_KIND[m.group(2)]
    return _QUARTER_BY_OFFSET.get((int(m.group(4)) - int(acc_mt)) % 12)


def filings(corp_code: str, start: str, end: str, key: str) -> list[dict]:
    """기간 안 정기공시 최종본 → [{"rcept_no", "report_code", "report_nm"}]."""
    acc_mt = _json("company.json", {"corp_code": corp_code}, key)["acc_mt"]
    out, page, total = [], 1, 1
    while page <= total:
        j = _json("list.json", {"corp_code": corp_code, "bgn_de": start, "end_de": end, "pblntf_ty": "A",
                                "last_reprt_at": "Y", "page_no": page, "page_count": 100}, key)
        total = int(j.get("total_page") or 1)
        for f in j.get("list", []):
            code = report_code(f["report_nm"], acc_mt)
            if code:
                out.append({"rcept_no": f["rcept_no"], "report_code": code, "report_nm": f["report_nm"].strip()})
        page += 1
    return out


def collect(corp_code: str, start: str, end: str, raw_dir: Path = RAW_DIR) -> dict:
    """회사 하나의 정기공시 XBRL zip을 받는다. 반환: {"saved", "exists", "no_xbrl"} 개수."""
    key = _api_key()
    out_dir = raw_dir / SOURCE / corp_code
    stats = {"saved": 0, "exists": 0, "no_xbrl": 0}
    for f in filings(corp_code, start, end, key):
        dest = out_dir / f"{f['rcept_no']}_{f['report_code']}.zip"
        if dest.exists():
            stats["exists"] += 1
            continue
        raw = _get("fnlttXbrl.xml", {"rcept_no": f["rcept_no"], "reprt_code": f["report_code"]}, key)
        if raw[:2] != b"PK":                            # zip 대신 오류 응답(XML)
            m = re.search(rb"<status>(\d+)</status>.*?<message>(.*?)</message>", raw, re.S)
            status, message = (m.group(1).decode(), m.group(2).decode("utf-8", "replace")) if m else ("", "")
            if status == "013":                         # XBRL 없음(소규모 회사 등)
                stats["no_xbrl"] += 1
                continue
            raise DartError(status, message or raw[:200].decode("utf-8", "replace"))
        dest.parent.mkdir(parents=True, exist_ok=True)
        tmp = dest.with_name(dest.name + ".part")
        tmp.write_bytes(raw)
        tmp.replace(dest)
        stats["saved"] += 1
    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("corp_codes", nargs="+", help="OpenDART 고유번호(8자리)")
    parser.add_argument("--from", dest="start", default=f"{date.today().year - 1}0101", help="접수일 시작 (YYYYMMDD)")
    parser.add_argument("--to", dest="end", default=date.today().strftime("%Y%m%d"), help="접수일 끝 (YYYYMMDD)")
    parser.add_argument("--raw-dir", type=Path, default=RAW_DIR, help=f"저장 루트 (기본: {RAW_DIR})")
    args = parser.parse_args()
    for corp_code in args.corp_codes:
        print(corp_code, collect(corp_code, args.start, args.end, args.raw_dir))


if __name__ == "__main__":
    main()
