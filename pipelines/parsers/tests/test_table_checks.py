"""PDF 표 구조 검증: 머리글 반복, 합계 검산 (Docling 없이 실행)."""
import unittest

from parsers.model import RawCell
from parsers.pdf import repeated_header, table_flags, total_mismatch


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


if __name__ == "__main__":
    unittest.main()
