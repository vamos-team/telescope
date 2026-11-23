"""그림 영역의 VLM 경로 (차트→표, 설명 생성)를 가짜 처리기로 확인한다.

그림 종류 구분 모델의 판정은 테스트 이미지에 따라 달라질 수 있다.
그래서 경로 판정(route_picture)은 고정하고, 판정 뒤의 처리 흐름만 확인한다.
경로 판정 규칙 자체는 test_vision.py에서 확인한다.
"""
import unittest
from pathlib import Path
from unittest import mock

import pymupdf

from parsers.loaders import load
from parsers.vision import PictureRoute, VisionResult, VisionTask

from .test_pdf_loader import FONT, _bar_chart_png


def make_pdf() -> bytes:
    """글자가 있는 쪽 1개 + 그 안의 글자 없는 그림 1개."""
    d = pymupdf.open()
    p = d.new_page()
    p.insert_text((72, 80), "2026년 2분기 가입자 추이", fontname=FONT, fontsize=20)
    p.insert_text((72, 120), "아래 그림은 분기별 가입자 수를 보여 준다.", fontname=FONT, fontsize=11)
    p.insert_image(pymupdf.Rect(72, 160, 520, 496), stream=_bar_chart_png())
    p.insert_text((72, 540), "가입자 수는 다섯 분기 연속 늘었다.", fontname=FONT, fontsize=11)
    raw = d.tobytes()
    d.close()
    return raw


class PictureRouteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = make_pdf()

    def run_route(self, route, handler=None):
        with mock.patch("parsers.pdf.route_picture", return_value=route):
            return load(Path("test.pdf"), raw=self.raw, vision=handler)

    def picture_blocks(self, doc, flag):
        return [b for b in doc.blocks if flag in b.flags]

    def test_picture_is_found_on_text_page(self):
        """전제: Docling이 글자 있는 쪽 안의 그림을 그림 영역으로 찾는다."""
        doc = self.run_route(PictureRoute.DESCRIBE)
        tags = [f for b in doc.blocks for f in b.flags if f.startswith("picture:")]
        self.assertTrue(tags, "그림 영역을 찾지 못함")
        texts = " ".join(b.text for b in doc.blocks)
        self.assertIn("가입자 추이", texts)
        self.assertIn("연속 늘었다", texts)

    def test_chart_to_table_with_handler(self):
        seen = []

        def handler(req):
            seen.append(req)
            return VisionResult(tables=[[["분기", "가입자"], ["1Q26", "120"], ["2Q26", "160"]]])

        doc = self.run_route(PictureRoute.CHART_VISION, handler)
        self.assertEqual([r.task for r in seen], [VisionTask.TO_TABLE])
        req = seen[0]
        self.assertEqual(req.page, 1)
        self.assertIsNotNone(req.bbox)
        self.assertTrue(req.image_png.startswith(b"\x89PNG"), "그림 이미지가 PNG가 아님")

        tables = [b for b in doc.blocks if b.kind == "table" and "chart" in b.flags]
        self.assertEqual(len(tables), 1)
        t = tables[0]
        self.assertEqual(t.origin, "vision_llm")
        self.assertIn("vision", t.flags)
        self.assertEqual(t.bbox, req.bbox)
        self.assertEqual([[c.text for c in row] for row in t.grid][2], ["2Q26", "160"])
        self.assertTrue(all(c.origin == "vision_llm" for row in t.grid for c in row))
        self.assertNotIn("needs_vision", [f for b in doc.blocks for f in b.flags])

    def test_chart_to_table_without_handler(self):
        doc = self.run_route(PictureRoute.CHART_VISION)
        marked = self.picture_blocks(doc, "needs_vision")
        self.assertEqual(len(marked), 1)
        self.assertIn("chart", marked[0].flags)
        self.assertIsNotNone(marked[0].bbox)
        self.assertTrue(any("needs_vision" in w for w in doc.warnings))

    def test_describe_with_handler(self):
        seen = []

        def handler(req):
            seen.append(req)
            return VisionResult(description="파란 막대 4개가 오른쪽으로 갈수록 높아지는 막대 차트")

        doc = self.run_route(PictureRoute.DESCRIBE, handler)
        self.assertEqual([r.task for r in seen], [VisionTask.DESCRIBE])
        desc = [b for b in doc.blocks if b.origin == "vision_llm"]
        self.assertEqual(len(desc), 1)
        self.assertIn("막대 차트", desc[0].text)
        self.assertIn("picture", desc[0].flags)
        self.assertIn("vision", desc[0].flags)
        self.assertEqual(desc[0].bbox, seen[0].bbox)
        # 글자 있는 문단은 코드가 읽은 그대로 남는다
        others = [b for b in doc.blocks if b.origin != "vision_llm" and b.text]
        self.assertTrue(all(b.origin == "disclosed_code" for b in others))

    def test_describe_without_handler(self):
        doc = self.run_route(PictureRoute.DESCRIBE)
        marked = self.picture_blocks(doc, "needs_vision")
        self.assertEqual(len(marked), 1)
        self.assertIn("picture", marked[0].flags)

    def test_handler_error_falls_back_to_mark(self):
        def handler(req):
            raise RuntimeError("model crashed")

        doc = self.run_route(PictureRoute.DESCRIBE, handler)
        self.assertEqual(len(self.picture_blocks(doc, "needs_vision")), 1)
        self.assertFalse([b for b in doc.blocks if b.origin == "vision_llm"])
        self.assertTrue(any("VLM 실패" in w for w in doc.warnings))


if __name__ == "__main__":
    unittest.main()
