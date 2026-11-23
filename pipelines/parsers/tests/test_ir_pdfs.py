"""실제 IR PDF 확인 (선택). PDF는 git에 올리지 않으므로 파일이 있을 때만 실행한다.

실행: PARSER_TEST_PDF_DIR=<통신 3사 2Q26 IR PDF가 있는 폴더> python -m unittest ...
파일 이름: 2Q26_KT_IR.pdf, 2Q26_LGU+_IR.pdf, 2Q26_SKT_IR.pdf
기대 값은 #46 비교 실험에서 PDF 화면을 보고 기록한 값이다.
"""
import os
import unittest
from pathlib import Path

from parsers.loaders import load

DIR = os.environ.get("PARSER_TEST_PDF_DIR", "")


def _doc(name):
    path = Path(DIR) / name
    if not DIR or not path.exists():
        raise unittest.SkipTest(f"{name} 없음 (PARSER_TEST_PDF_DIR)")
    return load(path)


def _chart_text(doc, page):
    return " ".join(b.text for b in doc.blocks if b.page == page and "chart" in b.flags)


class IrPdfTest(unittest.TestCase):
    def test_kt_charts_keep_text_layer_numbers(self):
        doc = _doc("2Q26_KT_IR.pdf")
        text = _chart_text(doc, 13)
        for v in ("1,704.8", "1,733.6", "1,735.7", "1,683.0", "1,674.9", "35,236", "34,412", "83.2%"):
            self.assertIn(v, text)
        self.assertTrue(all(b.origin == "disclosed_code" for b in doc.blocks if "chart" in b.flags))

    def test_kt_logo_page_is_not_sent_to_vlm(self):
        doc = _doc("2Q26_KT_IR.pdf")
        flags = [f for b in doc.blocks if b.page == 25 for f in b.flags]
        self.assertIn("decor_page", flags)
        self.assertNotIn("image_page", flags)

    def test_lgu_merged_table_is_flagged(self):
        doc = _doc("2Q26_LGU+_IR.pdf")
        flagged = [b.page for b in doc.tables if "repeated_header" in b.flags]
        self.assertEqual(flagged, [10])

    def test_skt_charts(self):
        doc = _doc("2Q26_SKT_IR.pdf")
        text = _chart_text(doc, 5)
        for v in ("7,173", "7,196", "7,238", "7,311", "7,348", "6,721", "6,709", "6,750", "6,754"):
            self.assertIn(v, text)


if __name__ == "__main__":
    unittest.main()
