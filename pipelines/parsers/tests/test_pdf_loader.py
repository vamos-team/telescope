"""Docling PDF 로더와 쪽 라우팅. 테스트 PDF는 PyMuPDF로 만든다(저장소에 PDF 파일을 두지 않음).

처음 실행할 때 Docling 모델(레이아웃·표 구조·그림 종류)을 내려받는다.
"""
import subprocess
import sys
import unittest
from pathlib import Path

import pymupdf

from parsers.loaders import load
from parsers.vision import VisionResult, VisionTask

FONT = "korea"   # PyMuPDF 내장 한국어 글꼴


def _bar_chart_png() -> bytes:
    """벡터 막대 차트를 그린 쪽을 이미지로 바꾼다 (글자 없는 차트 이미지)."""
    with pymupdf.open() as d:
        p = d.new_page(width=400, height=300)
        for i, h in enumerate((120, 160, 140, 200)):
            p.draw_rect(pymupdf.Rect(60 + i * 80, 260 - h, 110 + i * 80, 260), color=(0, 0, 0),
                        fill=(0.2, 0.4, 0.8))
        p.draw_line((40, 260), (380, 260))
        p.draw_line((40, 40), (40, 260))
        return p.get_pixmap(dpi=144).tobytes("png")


def make_pdf() -> bytes:
    d = pymupdf.open()
    # 1쪽: 제목, 문단, 테두리 표
    p = d.new_page()
    p.insert_text((72, 80), "2026년 2분기 실적 요약", fontname=FONT, fontsize=20)
    p.insert_text((72, 120), "회사의 2분기 매출은 전년 같은 기간보다 늘었다.", fontname=FONT, fontsize=11)
    rows = [["구분", "2Q25", "2Q26"], ["매출", "1,234", "1,300"], ["영업이익", "120", "150"]]
    x0, y0, cw, rh = 72, 160, 140, 28
    for r, row in enumerate(rows):
        for c, txt in enumerate(row):
            rect = pymupdf.Rect(x0 + c * cw, y0 + r * rh, x0 + (c + 1) * cw, y0 + (r + 1) * rh)
            p.draw_rect(rect, color=(0, 0, 0), width=0.8)
            p.insert_text((rect.x0 + 8, rect.y1 - 9), txt, fontname=FONT, fontsize=11)
    # 2쪽: 글자 없이 차트 이미지만
    p2 = d.new_page()
    p2.insert_image(pymupdf.Rect(72, 100, 520, 436), stream=_bar_chart_png())
    # 3쪽: 빈 쪽
    d.new_page()
    raw = d.tobytes()
    d.close()
    return raw


class PdfLoaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = make_pdf()
        cls.doc = load(Path("test.pdf"), raw=cls.raw)

    def blocks(self, page):
        return [b for b in self.doc.blocks if b.page == page]

    def test_page_count(self):
        self.assertEqual(self.doc.page_count, 3)

    def test_text_page_table(self):
        tables = [b for b in self.blocks(1) if b.kind == "table"]
        self.assertEqual(len(tables), 1)
        cells = [[c.text for c in row] for row in tables[0].grid]
        self.assertIn(["영업이익", "120", "150"], cells)
        self.assertEqual(tables[0].origin, "disclosed_code")
        self.assertIsNotNone(tables[0].bbox)

    def test_text_page_paragraph(self):
        texts = " ".join(b.text for b in self.blocks(1) if b.kind != "table")
        self.assertIn("2분기 매출", texts)

    def test_image_page_without_handler_is_marked(self):
        flags = [f for b in self.blocks(2) for f in b.flags]
        self.assertIn("needs_vision", flags)
        self.assertIn("image_page", flags)
        self.assertTrue(any("needs_vision" in w for w in self.doc.warnings))

    def test_empty_page_is_marked(self):
        self.assertIn("empty_page", [f for b in self.blocks(3) for f in b.flags])

    def test_handler_reads_image_page(self):
        seen = []

        def handler(req):
            seen.append((req.task, req.page))
            return VisionResult(tables=[[["구분", "값"], ["가", "1"]]])

        doc = load(Path("test.pdf"), raw=self.raw, vision=handler)
        self.assertIn((VisionTask.PAGE, 2), seen)
        vt = [b for b in doc.blocks if b.page == 2 and b.kind == "table"]
        self.assertEqual(len(vt), 1)
        self.assertEqual(vt[0].origin, "vision_llm")
        self.assertIn("image_page", vt[0].flags)
        self.assertTrue(all(c.origin == "vision_llm" for row in vt[0].grid for c in row))
        # 글자 있는 쪽은 VLM으로 보내지 않는다
        self.assertNotIn(1, [p for _, p in seen])


class NonPdfRoutingTest(unittest.TestCase):
    def test_html_does_not_load_docling(self):
        """PDF가 아닌 형식은 기존 로더를 쓰고 Docling을 불러오지 않는다."""
        html = ("<html><body><table><tr><th>구분</th><th>2Q25</th><th>2Q26</th></tr>"
                "<tr><td>매출</td><td>1,234</td><td>1,300</td></tr>"
                "<tr><td>영업이익</td><td>120</td><td>150</td></tr></table></body></html>")
        code = ("import sys; from pathlib import Path; from parsers.loaders import load; "
                f"d = load(Path('a.html'), raw={html!r}.encode()); "
                "assert d.file_type == 'html' and d.tables; "
                "assert 'docling' not in sys.modules, 'docling imported'")
        here = Path(__file__).resolve().parents[2]   # pipelines/
        subprocess.run([sys.executable, "-c", code], cwd=here, check=True)


if __name__ == "__main__":
    unittest.main()
