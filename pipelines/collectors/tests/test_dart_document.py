"""원문 XML 수집기: 저장, 다시 받지 않기, 오류 응답 (가짜 API, 네트워크 없음)."""
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from collectors import dart_document
from collectors.dart_xbrl import DartError

ZIP = b"PK\x03\x04fake"
FILINGS = [
    {"rcept_no": "20260318000123", "report_code": "11011", "period": "2025.12", "report_nm": "사업보고서 (2025.12)"},
    {"rcept_no": "20260515000456", "report_code": "11013", "period": "2026.03", "report_nm": "분기보고서 (2026.03)"},
]
ERROR = "<?xml version='1.0'?><result><status>014</status><message>파일이 존재하지 않습니다.</message></result>"


class CollectTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.calls = []
        patches = [mock.patch.object(dart_document, "_api_key", return_value="test-key"),
                   mock.patch.object(dart_document, "filings", return_value=FILINGS),
                   mock.patch.object(dart_document, "_get", side_effect=self.fake_get)]
        for p in patches:
            p.start()
            self.addCleanup(p.stop)
        self.response = ZIP

    def fake_get(self, endpoint, params, key):
        self.calls.append((endpoint, params))
        return self.response

    def test_saves_zip_and_meta(self):
        stats = dart_document.collect("00159023", "20260101", "20261231", self.dir)
        self.assertEqual(stats, {"saved": 2, "exists": 0})
        self.assertEqual([c[0] for c in self.calls], ["document.xml", "document.xml"])
        self.assertEqual(self.calls[0][1], {"rcept_no": "20260318000123"})
        out = self.dir / "dart_document" / "00159023"
        self.assertEqual((out / "20260318000123_11011.zip").read_bytes(), ZIP)
        meta = json.loads((out / "20260318000123_11011.json").read_text(encoding="utf-8"))
        self.assertEqual(meta["rcept_date"], "2026-03-18")
        self.assertEqual((meta["corp_code"], meta["report_nm"], meta["period"]),
                         ("00159023", "사업보고서 (2025.12)", "2025.12"))
        self.assertNotIn("test-key", json.dumps(meta))
        self.assertEqual(list(out.glob("*.part")), [])

    def test_skips_existing(self):
        dart_document.collect("00159023", "20260101", "20261231", self.dir)
        self.calls.clear()
        stats = dart_document.collect("00159023", "20260101", "20261231", self.dir)
        self.assertEqual(stats, {"saved": 0, "exists": 2})
        self.assertEqual(self.calls, [])

    def test_error_response_is_not_saved(self):
        self.response = ERROR.encode()
        with self.assertRaisesRegex(DartError, "014"):
            dart_document.collect("00159023", "20260101", "20261231", self.dir)
        self.assertEqual(list(self.dir.rglob("*.zip")), [])
        self.assertEqual(list(self.dir.rglob("*.json")), [])


class RceptDateTest(unittest.TestCase):
    def test_date(self):
        self.assertEqual(dart_document.rcept_date("20261116000789"), "2026-11-16")

    def test_bad_number(self):
        for bad in ("2026", "2026111600078x", ""):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                dart_document.rcept_date(bad)


if __name__ == "__main__":
    unittest.main()
