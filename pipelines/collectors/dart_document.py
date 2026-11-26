"""OpenDART 정기공시(사업·반기·분기보고서) 원문 XML zip 수집.

받은 바이트를 그대로 저장한다. 파싱·적재는 하지 않는다.
원문은 PDF와 내용이 같다(문장과 모든 표). 재무제표 숫자만 담은 XBRL(collectors/dart_xbrl.py)과 다르다.

저장 위치: data/raw/dart_document/<고유번호>/<접수번호>_<보고서코드>.zip
           data/raw/dart_document/<고유번호>/<접수번호>_<보고서코드>.json   공시 목록 API가 준 정보
    보고서코드: 11011 사업 / 11012 반기 / 11013 1분기 / 11014 3분기
    정정공시도 모두 받는다(문서 버전은 새로 쌓는다). 어느 버전을 쓸지는 적재 단계에서 정한다.

이미 받은 파일은 다시 받지 않는다.

실행 (pipelines 폴더에서): python -m collectors.dart_document <고유번호> ... [--from YYYYMMDD] [--to YYYYMMDD]
API 키: 환경변수 또는 저장소 루트 .env의 OPENDART_API_KEY
"""

from __future__ import annotations

import argparse
import json
import re
from datetime import date
from pathlib import Path

from collectors.dart_xbrl import RAW_DIR, DartError, _api_key, _get, filings

SOURCE = "dart_document"
_ERROR = re.compile(rb"<status>(\d+)</status>.*?<message>(.*?)</message>", re.S)


def _write(dest: Path, raw: bytes) -> None:
    """임시 파일에 쓰고 바꾼다. 중간에 멈춰도 반쯤 쓴 파일이 남지 않는다."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_name(dest.name + ".part")
    tmp.write_bytes(raw)
    tmp.replace(dest)


def rcept_date(rcept_no: str) -> str:
    """접수번호 앞 8자리(접수일 YYYYMMDD) → 'YYYY-MM-DD'."""
    if not re.fullmatch(r"\d{14}", rcept_no):
        raise ValueError(f"접수번호 형식이 아님: {rcept_no!r}")
    return f"{rcept_no[:4]}-{rcept_no[4:6]}-{rcept_no[6:8]}"


def download(rcept_no: str, key: str) -> bytes:
    """공시 하나의 원문 zip. zip이 아닌 응답(오류)이면 DartError."""
    raw = _get("document.xml", {"rcept_no": rcept_no}, key)
    if raw[:2] == b"PK":
        return raw
    m = _ERROR.search(raw)
    status, message = (m.group(1).decode(), m.group(2).decode("utf-8", "replace")) if m else ("", "")
    raise DartError(status, message or raw[:200].decode("utf-8", "replace"))


def collect(corp_code: str, start: str, end: str, raw_dir: Path = RAW_DIR) -> dict:
    """회사 하나의 기간 안 정기공시 원문 zip을 모두 받는다(정정공시 포함).
    반환: {"saved", "exists"} 공시 수."""
    key = _api_key()
    out_dir = raw_dir / SOURCE / corp_code
    stats = {"saved": 0, "exists": 0}
    for f in sorted(filings(corp_code, start, end, key), key=lambda f: f["rcept_no"]):
        base = out_dir / f"{f['rcept_no']}_{f['report_code']}"
        dest = base.with_suffix(".zip")
        if dest.exists():
            stats["exists"] += 1
            continue
        raw = download(f["rcept_no"], key)
        meta = {"corp_code": corp_code, **f, "rcept_date": rcept_date(f["rcept_no"]), "source": SOURCE}
        _write(base.with_suffix(".json"), json.dumps(meta, ensure_ascii=False, indent=2).encode())
        _write(dest, raw)                               # zip을 마지막에 쓴다: zip이 있으면 .json도 있다
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
        try:
            print(corp_code, collect(corp_code, args.start, args.end, args.raw_dir))
        except DartError as e:                          # 한 회사가 실패해도 다음 회사는 받는다
            print(corp_code, "실패:", e)


if __name__ == "__main__":
    main()
