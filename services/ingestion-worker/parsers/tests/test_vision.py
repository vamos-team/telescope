"""라우팅 규칙과 VLM 처리기 호출 (Docling 없이 실행)."""
import time
import unittest

from parsers.vision import (MIN_PAGE_CHARS, PageRoute, PictureRoute, VisionRequest, VisionResult,
                            VisionRunner, VisionTask, has_value_numbers, route_page, route_picture)


class RoutePageTest(unittest.TestCase):
    def test_text_page(self):
        self.assertEqual(route_page(MIN_PAGE_CHARS, ["photograph"]), PageRoute.TEXT)

    def test_empty_page(self):
        self.assertEqual(route_page(0, []), PageRoute.EMPTY)

    def test_logo_only_page_skips_vlm(self):
        self.assertEqual(route_page(3, ["logo", "icon"]), PageRoute.DECOR)

    def test_image_page_goes_to_vlm(self):
        self.assertEqual(route_page(0, ["logo", "bar_chart"]), PageRoute.VISION)

    def test_scanned_text_page_goes_to_vlm(self):
        # 스캔한 글자·표만 있는 쪽: Docling은 항목을 남기지 않지만 쪽 이미지에 내용이 보인다
        self.assertEqual(route_page(0, [], unread_content=True), PageRoute.VISION)
        self.assertEqual(route_page(0, ["logo"], unread_content=True), PageRoute.VISION)

    def test_text_page_ignores_unread_flag(self):
        self.assertEqual(route_page(MIN_PAGE_CHARS, [], unread_content=True), PageRoute.TEXT)


class RoutePictureTest(unittest.TestCase):
    def test_chart_with_text_layer(self):
        self.assertEqual(route_picture("bar_chart", ["1,704.8", "2Q25"]), PictureRoute.CHART_TEXT)

    def test_chart_without_text_layer(self):
        self.assertEqual(route_picture("line_chart", ["  "]), PictureRoute.CHART_VISION)

    def test_table_image_without_text(self):
        self.assertEqual(route_picture("table", []), PictureRoute.CHART_VISION)

    def test_logo_keeps_text_only(self):
        self.assertEqual(route_picture("logo", ["SK telecom"]), PictureRoute.DECOR)

    def test_chart_with_unit_or_legend_only_goes_to_vlm(self):
        # #65 측정: LG U+ 16쪽('단위 : 원'), KT 20쪽(범례 글자)만 텍스트 층에 있고 값은 이미지
        self.assertEqual(route_picture("bar_chart", ["단위 : 원"]), PictureRoute.CHART_VISION)
        self.assertEqual(route_picture("bar_chart", ["■ 주요 그룹사 CAPEX 금융, 미디어"]),
                         PictureRoute.CHART_VISION)

    def test_chart_with_periods_only_goes_to_vlm(self):
        self.assertEqual(route_picture("bar_chart", ["2Q25 3Q25 4Q25 1Q26 2Q26"]), PictureRoute.CHART_VISION)
        self.assertEqual(route_picture("bar_chart", ["2023 2024 2025 1H26", "25.2Q", "2026년 3월"]),
                         PictureRoute.CHART_VISION)

    def test_value_numbers(self):
        for texts in (["718 2023 2024"], ["QoQ-1 .1%"], ["35,236"], ["21.98"], ["2,025"]):
            with self.subTest(texts=texts):
                self.assertTrue(has_value_numbers(texts))
        for texts in ([], [""], ["단위 : 원"], ["2Q25", "1H26", "2025년", "12월"]):
            with self.subTest(texts=texts):
                self.assertFalse(has_value_numbers(texts))

    def test_photo_and_unknown_are_described(self):
        self.assertEqual(route_picture("photograph", []), PictureRoute.DESCRIBE)
        self.assertEqual(route_picture("", ["도식 안 글자"]), PictureRoute.DESCRIBE)


def _req(png=b"img", task=VisionTask.TO_TABLE):
    return VisionRequest(task, png, page=1)


class VisionRunnerTest(unittest.TestCase):
    def test_no_handler_returns_none(self):
        r = VisionRunner(None)
        self.assertFalse(r.enabled)
        self.assertIsNone(r.run(_req()))

    def test_same_image_called_once(self):
        calls = []

        def handler(req):
            calls.append(req.task)
            return VisionResult(tables=[[["a", "1"]]])

        r = VisionRunner(handler)
        r.run(_req())
        r.run(_req())
        r.run(_req(task=VisionTask.DESCRIBE))   # 작업이 다르면 따로 부른다
        r.close()
        self.assertEqual(calls, [VisionTask.TO_TABLE, VisionTask.DESCRIBE])

    def test_timeout_leaves_warning(self):
        r = VisionRunner(lambda req: time.sleep(0.5), timeout=0.05)
        self.assertIsNone(r.run(_req()))
        r.close()
        self.assertTrue(any("시간 제한" in w for w in r.warnings))

    def test_handler_error_leaves_warning(self):
        def handler(req):
            raise RuntimeError("모델 없음")

        r = VisionRunner(handler)
        self.assertIsNone(r.run(_req()))
        r.close()
        self.assertTrue(any("모델 없음" in w for w in r.warnings))

    def test_dict_result_is_accepted(self):
        r = VisionRunner(lambda req: {"paragraphs": ["설명"], "tables": []})
        res = r.run(_req())
        r.close()
        self.assertEqual(res.paragraphs, ["설명"])

    def test_wrong_result_type_leaves_warning(self):
        r = VisionRunner(lambda req: "문자열")
        self.assertIsNone(r.run(_req()))
        r.close()
        self.assertTrue(any("반환 형식" in w for w in r.warnings))

    def test_call_limit(self):
        calls = []

        def handler(req):
            calls.append(req.image_png)
            return VisionResult(description="설명")

        r = VisionRunner(handler, max_calls=2)
        results = [r.run(_req(png=bytes([i]))) for i in range(4)]
        self.assertIsNotNone(r.run(_req(png=bytes([0]))))   # 캐시는 상한에 세지 않는다
        r.close()
        self.assertEqual(len(calls), 2)
        self.assertEqual([x is not None for x in results], [True, True, False, False])
        self.assertTrue(any("호출 상한(2번)" in w and "2개" in w for w in r.warnings))

    def test_no_call_limit_by_default(self):
        r = VisionRunner(lambda req: VisionResult(description="설명"))
        self.assertTrue(all(r.run(_req(png=bytes([i]))) for i in range(40)))
        r.close()
        self.assertEqual(r.warnings, [])


if __name__ == "__main__":
    unittest.main()
