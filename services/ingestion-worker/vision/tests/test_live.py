"""실제 OpenAI API 호출 (선택). 비용이 들므로 두 환경 변수가 모두 있을 때만 실행한다.

실행: OPENAI_API_KEY=... VISION_LIVE_TEST=1 python -m unittest vision.tests.test_live
보내는 이미지는 테스트가 그린 가짜 차트다. 실제 문서는 보내지 않는다.
"""
import os
import unittest

from parsers.pdf import vision_table_flags
from parsers.vision import VisionRequest, VisionTask
from vision import from_env

VALUES = ["1,704.8", "1,733.6", "1,735.7", "1,683.0", "1,674.9"]
PERIODS = ["2Q25", "3Q25", "4Q25", "1Q26", "2Q26"]


def _chart_png() -> bytes:
    """값 글자가 그림 안에 있는 막대 차트를 이미지로 그린다 (텍스트 층 없는 차트와 같은 상태)."""
    import pymupdf
    with pymupdf.open() as d:
        p = d.new_page(width=520, height=320)
        p.insert_text((20, 30), "Service revenue (KRW bn)", fontsize=13)
        for i, (v, q) in enumerate(zip(VALUES, PERIODS)):
            h = 60 + (float(v.replace(",", "")) - 1650) * 1.5
            x = 40 + i * 95
            p.draw_rect(pymupdf.Rect(x, 280 - h, x + 60, 280), color=(0, 0, 0), fill=(0.2, 0.4, 0.8))
            p.insert_text((x + 2, 272 - h), v, fontsize=12)
            p.insert_text((x + 12, 300), q, fontsize=12)
        p.insert_text((380, 30), "QoQ -0.5%", fontsize=12)
        return p.get_pixmap(dpi=144).tobytes("png")


@unittest.skipUnless(os.environ.get("OPENAI_API_KEY") and os.environ.get("VISION_LIVE_TEST") == "1",
                     "OPENAI_API_KEY와 VISION_LIVE_TEST=1이 있을 때만 실행")
class LiveTest(unittest.TestCase):
    def test_chart_to_table(self):
        h = from_env()
        res = h(VisionRequest(VisionTask.TO_TABLE, _chart_png(), page=1, picture_class="bar_chart"))
        cells = [c for row in res.tables[0] for c in row]
        for v in VALUES:
            self.assertIn(v, cells)
        self.assertEqual(vision_table_flags(res.tables[0]), [])
        self.assertEqual(h.usage.calls, 1)
        self.assertGreater(h.usage.input_tokens, 0)

    def test_describe(self):
        h = from_env()
        res = h(VisionRequest(VisionTask.DESCRIBE, _chart_png(), page=1, picture_class="photograph"))
        self.assertTrue(res.description)


if __name__ == "__main__":
    unittest.main()
