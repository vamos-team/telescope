"""파서 결과 → LLM용 마크다운 변환과 나누기."""
import os
import tempfile
import unittest
from pathlib import Path

from parsers import parse, split_markdown, to_markdown
from parsers.markdown import VISION_MARK, table_markdown


def _table(rows, header_rows=1, **kw):
    return {"table_no": 0, "rows": rows, "header_rows": header_rows, "caption": "", "unit": "",
            "period_hint": "", **kw}


def _res(blocks, tables=(), file_type="pdf", file="/x/report.pdf", warnings=()):
    full = []
    for b in blocks:
        full.append({"kind": "paragraph", "level": 0, "section_path": [], "text": "", "page": None,
                     "sheet": None, "table_no": None, "flags": [], "origin": "disclosed_code", "bbox": None, **b})
    return {"file": file, "file_type": file_type, "title": "", "page_count": None,
            "warnings": list(warnings), "blocks": full, "tables": list(tables)}


class TableMarkdownTest(unittest.TestCase):
    def test_banner_rows_and_combined_header(self):
        # DART 잠정실적 공시 표 모양: 주석 행, 제목·단위 행, 두 줄 머리글
        rows = [["※ 잠정치임"] * 5,
                ["1. 연결실적", "1. 연결실적", "단위 : 억원", "단위 : 억원", "단위 : 억원"],
                ["구분", "구분", "당기실적", "전기대비", "전기대비"],
                ["구분", "구분", "('26.2분기)", "증감율(%)", "흑자적자전환여부"],
                ["매출액", "당해실적", "43,591", "-0.76", "-"],
                ["매출액", "누계실적", "87,514", "-", "-"]]
        tm = table_markdown(_table(rows, header_rows=4, unit="억원"), [], "disclosed_code")
        md = tm.render()
        self.assertIn("※ 잠정치임", tm.notes)
        self.assertIn("1. 연결실적 · 단위 : 억원", tm.notes)
        self.assertIn("단위: 억원", tm.notes)
        self.assertIn("| 구분 |  | 당기실적 / ('26.2분기) | 전기대비 / 증감율(%) | 전기대비 / 흑자적자전환여부 |", md)
        self.assertIn("| 매출액 | 당해실적 | 43,591 | -0.76 | - |", md)

    def test_column_group_header_is_not_banner(self):
        rows = [["구분", "당기", "당기", "당기", "당기"], ["", "1Q", "2Q", "3Q", "4Q"], ["매출", "1", "2", "3", "4"]]
        md = table_markdown(_table(rows, header_rows=2), [], "").render()
        self.assertIn("| 구분 | 당기 / 1Q | 당기 / 2Q | 당기 / 3Q | 당기 / 4Q |", md)

    def test_wide_table_common_header_and_column_split(self):
        top = "재화나 용역의 이전 시기 / 한 시점에 이전하는 재화나 서비스"
        heads = [["구분"] + ["재화나 용역의 이전 시기"] * 30, ["구분"] + ["한 시점에 이전하는 재화나 서비스"] * 30,
                 ["구분"] + [f"제품{i:02d} 상세 이름" for i in range(30)]]
        rows = heads + [["매출"] + [f"{i},000" for i in range(30)]]
        res = _res([{"kind": "table", "table_no": 0, "page": 1}], [_table(rows, header_rows=3)])
        md = to_markdown(res)
        self.assertIn("열 머리글 공통: " + top, md)
        self.assertIn("| 구분 | 제품00 상세 이름 |", md)
        chunks = split_markdown(res, max_chars=600)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(c["chars"] <= 600 for c in chunks))
        self.assertTrue(all("| 구분 |" in c["text"] and "| 매출 |" in c["text"] for c in chunks))
        joined = "\n".join(c["text"] for c in chunks)
        for i in range(30):
            self.assertEqual(joined.count(f" {i},000 |"), 1)

    def test_merged_labels_blank_but_numbers_kept(self):
        rows = [["구분", "2025", "2026"], ["매출", "매출", "100"], ["이익", "0", "0"]]
        md = table_markdown(_table(rows), [], "").render()
        self.assertIn("| 매출 |  | 100 |", md)      # 병합 복제된 행 이름은 비움
        self.assertIn("| 이익 | 0 | 0 |", md)       # 같은 숫자는 그대로

    def test_escape_and_empty_columns(self):
        rows = [["항목", "", "값"], ["a|b", "", "줄\n바꿈"]]
        md = table_markdown(_table(rows), [], "").render()
        self.assertIn("| a\\|b | 줄 바꿈 |", md)
        self.assertNotIn("|  |  |", md)              # 모든 행이 빈 열은 빠짐
        self.assertEqual(md.count("\n|---|---|"), 1)

    def test_excel_header_years(self):
        rows = [["", "2022.0", "2023.0"], ["매출", "4619.0", "4487.4"]]
        md = table_markdown(_table(rows), [], "", file_type="xlsx").render()
        self.assertIn("|  | 2022 | 2023 |", md)
        self.assertIn("| 매출 | 4619.0 | 4487.4 |", md)   # 값은 그대로

    def test_vision_and_structure_warnings(self):
        tm = table_markdown(_table([["a", "b"], ["1", "2"]]), ["vision", "growth_mismatch"], "vision_llm")
        text = "\n".join(tm.notes)
        self.assertIn(VISION_MARK, text)
        self.assertIn("증감률이 값과 맞지 않음", text)


class ToMarkdownTest(unittest.TestCase):
    def setUp(self):
        t0 = _table([["구분", "2Q26"], ["매출", "1,674.9"]], unit="십억원", period_hint="2026년 2분기",
                    caption="서비스매출")
        t1 = dict(_table([["분기", "가입자"], ["2Q26", "160"]]), table_no=1)
        self.res = _res([
            {"kind": "heading", "level": 1, "text": "3-1 KT - 무선", "page": 13},
            {"text": "서비스매출", "page": 13},
            {"kind": "table", "table_no": 0, "page": 13},
            {"text": "1,704.8 1,674.9 2Q25 2Q26", "page": 13, "flags": ["chart", "picture:bar_chart"]},
            {"text": "단위 : 원", "page": 14, "flags": ["picture_text", "picture:bar_chart"], "bbox": (1, 2, 3, 4)},
            {"text": "범례", "page": 14, "flags": ["picture_text", "picture:bar_chart"], "bbox": (1, 2, 3, 4)},
            {"kind": "table", "table_no": 1, "page": 14, "flags": ["vision", "chart"], "origin": "vision_llm"},
            {"text": "", "page": 15, "flags": ["needs_vision", "image_page"]},
            {"text": "", "page": 16, "flags": ["empty_page"]},
            {"text": "", "page": 17, "flags": ["decor_page"]},
            {"text": "바다를 보는 두 사람", "page": 18, "flags": ["vision", "picture"], "origin": "vision_llm"},
        ], [t0, t1], warnings=["VLM이 필요한 영역 1개"])
        self.md = to_markdown(self.res)

    def test_header_and_page_markers(self):
        self.assertTrue(self.md.startswith("# report.pdf\n\n원본: report.pdf (pdf)"))
        self.assertEqual(self.md.count("[p.13]"), 1)        # 쪽이 바뀔 때만
        self.assertIn("[p.14]", self.md)
        self.assertNotIn("[p.16]", self.md)                 # 빈 쪽·장식 쪽은 뺀다
        self.assertNotIn("[p.17]", self.md)
        self.assertIn("## 3-1 KT - 무선", self.md)

    def test_table_meta_and_caption_not_repeated(self):
        self.assertIn("단위: 십억원 · 기간: 2026년 2분기", self.md)
        self.assertNotIn("표: 서비스매출", self.md)         # 바로 앞 문단과 같은 캡션은 생략
        self.assertIn("| 매출 | 1,674.9 |", self.md)

    def test_marks(self):
        self.assertIn("[차트 안 글자, 순서가 섞일 수 있음] 1,704.8", self.md)
        self.assertIn("[그림 안 글자] 단위 : 원 / 범례", self.md)   # 같은 그림의 글자는 한 줄
        self.assertIn(f"> {VISION_MARK} VLM이 이미지에서 옮긴 표", self.md)
        self.assertIn(f"> {VISION_MARK} 바다를 보는 두 사람", self.md)
        self.assertIn("> [읽지 못한 쪽: 이미지에만 있는 내용, VLM 처리 안 됨]", self.md)
        self.assertIn("## 파싱 경고\n\n- VLM이 필요한 영역 1개", self.md)

    def test_sheet_marker(self):
        res = _res([{"text": "가", "sheet": "손익", "page": 1}, {"text": "나", "sheet": "재무상태", "page": 2}],
                   file_type="xlsx", file="/x/a.xlsx")
        md = to_markdown(res)
        self.assertIn("[시트: 손익]", md)
        self.assertIn("[시트: 재무상태]", md)
        self.assertNotIn("[p.", md)

    def test_no_marker_for_html(self):
        res = _res([{"text": "가", "page": None}], file_type="html", file="/x/a.html")
        self.assertNotIn("[p.", to_markdown(res))


class SplitTest(unittest.TestCase):
    def test_sections_and_limit(self):
        blocks = []
        for s in range(3):
            sec = [f"{s + 1}장", "개요"]
            blocks.append({"kind": "heading", "level": 1, "text": f"{s + 1}장", "section_path": sec[:1], "page": s + 1})
            for k in range(8):
                blocks.append({"text": f"{s + 1}장 문단 {k} " + "가나다라마바사. " * 30, "section_path": sec,
                               "page": s + 1})
        chunks = split_markdown(_res(blocks), max_chars=1500)
        self.assertTrue(all(c["chars"] <= 1500 for c in chunks))
        for c in chunks:   # 한 조각에 서로 다른 장이 섞이지 않음 (각 장이 충분히 김)
            self.assertEqual(len({p[0] for p in c["section_paths"] if p}), 1)
            self.assertTrue(c["text"].startswith("[문서: report.pdf · 위치: "))
            self.assertIn(f"[p.{c['page_start']}]", c["text"])
        self.assertEqual([c["index"] for c in chunks], list(range(len(chunks))))

    def test_small_sections_are_merged(self):
        blocks = [{"text": f"짧은 절 {i}", "section_path": [f"{i}절"], "page": 1} for i in range(5)]
        chunks = split_markdown(_res(blocks), max_chars=4000)
        self.assertEqual(len(chunks), 1)
        self.assertIn("짧은 절 0", chunks[0]["text"])
        self.assertIn("짧은 절 4", chunks[0]["text"])

    def test_long_table_repeats_header(self):
        rows = [["항목", "2025", "2026"]] + [[f"계정{i:03d}", f"{i},000", f"{i},500"] for i in range(200)]
        res = _res([{"kind": "table", "table_no": 0, "page": 3}], [_table(rows, unit="백만원")])
        chunks = split_markdown(res, max_chars=1200)
        self.assertGreater(len(chunks), 1)
        n = len(chunks)
        for k, c in enumerate(chunks):
            self.assertLessEqual(c["chars"], 1200)
            self.assertIn("| 항목 | 2025 | 2026 |", c["text"])
            self.assertIn("단위: 백만원", c["text"])
            self.assertIn(f"(표 계속 {k + 1}/{n})", c["text"])
        body = "\n".join(c["text"] for c in chunks)
        for i in (0, 99, 199):                       # 행이 빠지거나 겹치지 않음
            self.assertEqual(body.count(f"| 계정{i:03d} |"), 1)

    def test_long_paragraph_is_split(self):
        res = _res([{"text": "문장입니다. " * 600, "page": 1}])
        chunks = split_markdown(res, max_chars=1000)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(c["chars"] <= 1000 for c in chunks))


class FormatTest(unittest.TestCase):
    """실제 로더를 거친 결과 (HTML, Excel, PDF)."""

    def _parse_bytes(self, name, raw):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / name
            p.write_bytes(raw)
            return parse(p)

    def test_html(self):
        html = ("<html><body><p>연결재무제표 기준 영업(잠정)실적</p><table>"
                "<tr><td>구분</td><td>당기실적</td></tr><tr><td>매출액</td><td>43,591</td></tr>"
                "</table></body></html>")
        md = to_markdown(self._parse_bytes("a.html", html.encode()))
        self.assertIn("| 매출액 | 43,591 |", md)
        self.assertIn("영업(잠정)실적", md)

    def test_excel(self):
        import io
        import openpyxl
        wb = openpyxl.Workbook()
        ws = wb.active
        ws.title = "손익계산서"
        ws.append(["KRW Billion", 2024, 2025])
        ws.append(["영업수익", 18579.7, 19324.0])
        buf = io.BytesIO()
        wb.save(buf)
        md = to_markdown(self._parse_bytes("fs.xlsx", buf.getvalue()))
        self.assertIn("[시트: 손익계산서]", md)
        self.assertIn("| 영업수익 | 18579.7 | 19324.0 |", md)

    def test_pdf(self):
        from .test_pdf_loader import make_pdf
        md = to_markdown(self._parse_bytes("t.pdf", make_pdf()))
        self.assertIn("[p.1]", md)
        self.assertIn("| 영업이익 | 120 | 150 |", md)
        self.assertIn("[p.2]", md)
        self.assertIn("[읽지 못한 쪽", md)
        self.assertNotIn("[p.3]", md)          # 빈 쪽


@unittest.skipUnless(os.environ.get("PARSER_TEST_DART_XML"), "PARSER_TEST_DART_XML(사업보고서 XML 경로)이 있을 때만")
class LargeDartTest(unittest.TestCase):
    def test_annual_report_split(self):
        res = parse(os.environ["PARSER_TEST_DART_XML"])
        chunks = split_markdown(res)
        self.assertGreater(len(chunks), 50)
        self.assertTrue(all(c["chars"] <= 4000 for c in chunks), max(c["chars"] for c in chunks))
        # 표 본문 행이 조각 사이에서 빠지지 않음: 마크다운 전체의 표 행 수 = 조각들의 표 본문 행 수
        md = to_markdown(res)
        # 숫자가 조각 사이에서 빠지지 않음 (열을 나눈 표는 행 이름이 반복되므로 숫자 개수로 비교)
        import re
        from collections import Counter
        nums = lambda t: Counter(re.findall(r"\d[\d,]*(?:\.\d+)?", "\n".join(
            l for l in t.splitlines() if l.startswith("| "))))
        whole, parts = nums(md), sum((nums(c["text"]) for c in chunks), Counter())
        self.assertEqual({k: v for k, v in whole.items() if parts[k] < v}, {})

if __name__ == "__main__":
    unittest.main()
