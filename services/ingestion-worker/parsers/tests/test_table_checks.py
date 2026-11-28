"""PDF 표 구조 검증: 머리글 반복, 합계 검산 (Docling 없이 실행)."""
import unittest

from parsers.model import RawCell
from parsers.pdf import (growth_mismatch, repeated_header, table_flags, total_mismatch,
                         vision_table_flags)


def g(rows):
    return [[RawCell(x) for x in row] for row in rows]


class RepeatedHeaderTest(unittest.TestCase):
    def test_two_tables_merged(self):
        # LG U+ 2Q26 IR 10쪽: 영업비용 표 아래에 CAPEX 표가 합쳐진 모양
        grid = g([["", "2Q25", "3Q25", "4Q25", "1Q26", "2Q26", "YoY"],
                  ["영업비용", "3,185.8", "3,557.0", "3,367.9", "3,274.9", "3,105.3", "-2.5%"],
                  ["※ 별도기준"] * 7,
                  ["", "2Q25", "3Q25", "4Q25", "1Q26", "2Q26", "YoY"],
                  ["CAPEX", "393.3", "", "", "317.2", "477.8", ""]])
        self.assertTrue(repeated_header(grid))
        self.assertIn("repeated_header", table_flags(grid))

    def test_normal_table(self):
        grid = g([["구분", "2Q25", "2Q26", "YoY"],
                  ["매출", "100", "110", "10.0%"],
                  ["영업이익", "10", "12", "20.0%"]])
        self.assertFalse(repeated_header(grid))

    def test_merged_note_row_is_not_header(self):
        grid = g([["구분", "2Q25", "2Q26", "YoY"],
                  ["2Q25", "2Q25", "2Q25", "2Q25"],     # 한 행 전체가 같은 글자(병합 주석 행)
                  ["매출", "100", "110", "10.0%"]])
        self.assertFalse(repeated_header(grid))


class TotalMismatchTest(unittest.TestCase):
    def test_total_matches(self):
        grid = g([["구분", "당기", "전기"],
                  ["유동자산", "7,292,630", "6,727,130"],
                  ["비유동자산", "25,137,521", "23,380,653"],
                  ["자산 합계", "32,430,151", "30,107,783"]])
        self.assertFalse(total_mismatch(grid))

    def test_total_does_not_match(self):
        grid = g([["구분", "당기", "전기"],
                  ["유동자산", "7,292,630", "6,727,130"],
                  ["비유동자산", "25,137,521", "23,380,653"],
                  ["자산 합계", "30,107,783", "30,107,783"]])   # 당기 값이 밀려 들어옴
        self.assertTrue(total_mismatch(grid))

    def test_rounding_is_tolerated(self):
        grid = g([["구분", "금액"], ["가", "1.04"], ["나", "2.04"], ["합계", "3.1"]])
        self.assertFalse(total_mismatch(grid))

    def test_hierarchy_children_marked_with_dash(self):
        grid = g([["구분", "금액"],
                  ["매출", "100"], ["- 서비스", "70"], ["- 상품", "30"],
                  ["비용", "60"],
                  ["합계", "160"]])
        self.assertFalse(total_mismatch(grid))

    def test_year_header_is_not_added(self):
        grid = g([["구분", "2024", "2025"],
                  ["가", "10", "20"], ["나", "5", "5"], ["합계", "15", "25"]])
        self.assertFalse(total_mismatch(grid))

    def test_percent_and_single_item_are_skipped(self):
        grid = g([["구분", "금액", "비중"], ["가", "100", "40%"], ["합계", "100", "100%"]])
        self.assertFalse(total_mismatch(grid))

    def test_sections_are_checked_separately(self):
        grid = g([["구분", "금액"],
                  ["가", "1"], ["나", "2"], ["소계", "3"],
                  ["다", "4"], ["라", "5"], ["소계", "9"]])
        self.assertFalse(total_mismatch(grid))


# #65 실험에서 모델이 실제로 돌려준 표 (LG U+ 2Q26 IR 6쪽 차트)
LGU_P6_RIGHT = [  # gpt-6.2-sol: 증감률이 맞는 계열에 붙음
    ["기간", "연결 서비스수익", "기업인프라", "스마트홈", "모바일", "연결 서비스수익 YoY", "기업인프라 YoY",
     "스마트홈 YoY", "모바일 YoY", "연결 영업이익", "연결 영업이익 YoY", "연결 영업이익 QoQ"],
    ["2Q25", "3,016", "428", "637", "1,645", "", "", "", "", "305", "", ""],
    ["1Q26", "", "", "", "", "", "", "", "", "272", "", ""],
    ["2Q26", "3,077", "464", "664", "1,660", "+2.0%", "+8.6%", "+4.3%", "+0.9%", "344", "+13.1%", "+26.5%"]]
LGU_P6_WRONG = [  # gpt-6-luna: 영업이익 증감률을 모바일 줄에 넣고 YoY를 엇갈리게 넣음
    ["차트", "기간", "합계", "기업인프라", "스마트홈", "모바일", "YoY", "QoQ"],
    ["연결 서비스수익", "2Q25", "3,016", "428", "637", "1,645", "", ""],
    ["연결 서비스수익", "2Q26", "3,077", "464", "664", "1,660", "+2.0%", ""],
    ["연결 서비스수익", "2Q26", "", "", "", "", "+8.6%", ""],
    ["연결 서비스수익", "2Q26", "", "", "", "", "+4.3%", ""],
    ["연결 서비스수익", "2Q26", "", "", "", "", "+0.9%", ""],
    ["연결 영업이익", "2Q25", "305", "", "", "", "", ""],
    ["연결 영업이익", "1Q26", "272", "", "", "", "", ""],
    ["연결 영업이익", "2Q26", "344", "", "", "+13.1%", "+26.5%"]]


class GrowthMismatchTest(unittest.TestCase):
    def test_right_pairing_passes(self):
        # 기업인프라 464/428 = +8.41%. 표시값 반올림 범위(+8.17%~+8.66%) 안이라 +8.6%는 통과
        self.assertFalse(growth_mismatch(LGU_P6_RIGHT))

    def test_wrong_pairing_is_caught(self):
        self.assertTrue(growth_mismatch(LGU_P6_WRONG))

    def test_single_series_with_plain_header(self):
        ok = [["기간", "서비스매출", "QoQ", "YoY"],
              ["2Q25", "1,704.8", "", ""], ["1Q26", "1,683.0", "", ""],
              ["2Q26", "1,674.9", "-0.5%", "-1.8%"]]
        self.assertFalse(growth_mismatch(ok))
        bad = [row[:] for row in ok]
        bad[3][2] = "-1.8%"   # QoQ와 YoY가 바뀜
        bad[3][3] = "-0.5%"
        self.assertTrue(growth_mismatch(bad))

    def test_periods_in_header_row(self):
        grid = [["구분", "2Q25", "1Q26", "2Q26"],
                ["매출", "100", "110", "121"],
                ["YoY", "", "", "21.0%"]]
        self.assertFalse(growth_mismatch(grid))
        grid[2][3] = "10.0%"
        self.assertTrue(growth_mismatch(grid))

    def test_missing_base_period_is_skipped(self):
        grid = [["기간", "매출", "YoY"], ["1Q26", "100", ""], ["2Q26", "150", "+99.0%"]]
        self.assertFalse(growth_mismatch(grid))   # 2Q25가 없어 YoY를 확인할 수 없음

    def test_percent_point_is_skipped(self):
        grid = [["기간", "비율", "YoY"], ["2Q25", "80.0%", ""], ["2Q26", "83.2%", "+3.2%p"]]
        self.assertFalse(growth_mismatch(grid))


class VisionTableFlagsTest(unittest.TestCase):
    def test_clean_table(self):
        self.assertEqual(vision_table_flags(LGU_P6_RIGHT), [])

    def test_wrong_table(self):
        flags = vision_table_flags(LGU_P6_WRONG)
        self.assertIn("growth_mismatch", flags)
        self.assertIn("vision_ragged", flags)   # 마지막 행 칸 수가 다름

    def test_bad_number(self):
        grid = [["기간", "매출"], ["2Q25", "1,23.4.5"], ["2Q26", "100"]]
        self.assertIn("vision_bad_number", vision_table_flags(grid))
        self.assertNotIn("vision_bad_number", vision_table_flags([["기간", "매출"], ["2Q25", "-"]]))
        # KT 2Q26 IR 스캔 쪽에서 나온 정상 칸: 단위, 각주 표시, 설명 글자
        grid = [["구분", "2025", "2026"], ["용량", "152MW*", "1.15GW"], ["비중", "6.7%*", "9% 이상"],
                ["거점", "가산, 목동, 용산 등 9개소", "대전 등 4개소"]]
        self.assertNotIn("vision_bad_number", vision_table_flags(grid))
        # SKT 2Q26 IR 4쪽: 추정치 꼬리표
        self.assertEqual(vision_table_flags([["기간", "5G M/S"], ["1Q26", "45.7%"], ["2Q26", "45.7%(e)"]]), [])


if __name__ == "__main__":
    unittest.main()
