"""OpenAI 처리기: 요청 만들기, 답 변환, 오류 (가짜 클라이언트, 네트워크 없음)."""
import base64
import json
import logging
import os
import unittest
from types import SimpleNamespace
from unittest import mock

from parsers.vision import VisionRequest, VisionRunner, VisionTask
from vision import DEFAULT_MODEL, OpenAIVisionHandler, VisionFormatError, from_env

PNG = b"\x89PNG\r\n\x1a\nfake"


class FakeClient:
    """client.responses.create(**kwargs)를 흉내 낸다."""

    def __init__(self, text="", status="completed", reason="", tokens=(500, 100), error=None):
        self.calls = []
        self.text, self.status, self.reason, self.tokens, self.error = text, status, reason, tokens, error
        self.responses = SimpleNamespace(create=self.create)

    def create(self, **kwargs):
        self.calls.append(kwargs)
        if self.error:
            raise self.error
        return SimpleNamespace(
            status=self.status, output_text=self.text,
            incomplete_details=SimpleNamespace(reason=self.reason) if self.reason else None,
            usage=SimpleNamespace(input_tokens=self.tokens[0], output_tokens=self.tokens[1]))


def _req(task=VisionTask.TO_TABLE):
    return VisionRequest(task, PNG, page=3, bbox=(1, 2, 3, 4), picture_class="bar_chart")


def _rows(*rows):
    return json.dumps({"rows": [list(r) for r in rows]}, ensure_ascii=False)


class RequestTest(unittest.TestCase):
    def test_to_table_request(self):
        client = FakeClient(_rows(["기간", "매출"], ["2Q26", "1,674.9"]))
        OpenAIVisionHandler(client=client)(_req())
        body = client.calls[0]
        self.assertEqual(body["model"], DEFAULT_MODEL)
        self.assertIs(body["store"], False)
        content = body["input"][0]["content"]
        self.assertEqual(content[1]["image_url"], "data:image/png;base64," + base64.b64encode(PNG).decode())
        fmt = body["text"]["format"]
        self.assertEqual((fmt["type"], fmt["strict"]), ("json_schema", True))
        self.assertIn("rows", fmt["schema"]["properties"])
        self.assertIn("추정하지 않는다", content[0]["text"])

    def test_describe_has_no_schema(self):
        client = FakeClient("막대 차트")
        OpenAIVisionHandler(client=client)(_req(VisionTask.DESCRIBE))
        self.assertNotIn("text", client.calls[0])

    def test_page_schema(self):
        client = FakeClient(json.dumps({"paragraphs": ["제목"], "tables": []}))
        OpenAIVisionHandler(client=client, model="gpt-6.2-sol")(_req(VisionTask.PAGE))
        body = client.calls[0]
        self.assertEqual(body["model"], "gpt-6.2-sol")
        self.assertEqual(set(body["text"]["format"]["schema"]["required"]), {"paragraphs", "tables"})


class ParseTest(unittest.TestCase):
    def test_to_table(self):
        h = OpenAIVisionHandler(client=FakeClient(_rows(["기간", "매출"], ["2Q26", " 1,674.9 "], ["", ""])))
        res = h(_req())
        self.assertEqual(res.tables, [[["기간", "매출"], ["2Q26", "1,674.9"]]])   # 빈 행은 버림

    def test_page(self):
        text = json.dumps({"paragraphs": ["2분기 실적", " "], "tables": [[["구분", "값"], ["가", "1"]], []]})
        res = OpenAIVisionHandler(client=FakeClient(text))(_req(VisionTask.PAGE))
        self.assertEqual(res.paragraphs, ["2분기 실적"])
        self.assertEqual(res.tables, [[["구분", "값"], ["가", "1"]]])

    def test_describe(self):
        res = OpenAIVisionHandler(client=FakeClient(" 바다를 보는\n두 사람 ")) (_req(VisionTask.DESCRIBE))
        self.assertEqual(res.description, "바다를 보는 두 사람")

    def test_format_errors(self):
        cases = [
            (VisionTask.TO_TABLE, "표 아님"),
            (VisionTask.TO_TABLE, "[]"),
            (VisionTask.TO_TABLE, _rows(["기간", "매출"])),            # 머리글뿐
            (VisionTask.TO_TABLE, json.dumps({"rows": "a"})),
            (VisionTask.PAGE, json.dumps({"paragraphs": [], "tables": []})),
            (VisionTask.DESCRIBE, "  "),
        ]
        for task, text in cases:
            with self.subTest(text=text), self.assertRaises(VisionFormatError):
                OpenAIVisionHandler(client=FakeClient(text))(_req(task))

    def test_incomplete_response(self):
        h = OpenAIVisionHandler(client=FakeClient("", status="incomplete", reason="max_output_tokens"))
        with self.assertRaisesRegex(VisionFormatError, "max_output_tokens"):
            h(_req())


class UsageAndLogTest(unittest.TestCase):
    def test_usage_is_accumulated(self):
        h = OpenAIVisionHandler(client=FakeClient("설명", tokens=(450, 120)))
        h(_req(VisionTask.DESCRIBE))
        h(_req(VisionTask.DESCRIBE))
        self.assertEqual((h.usage.calls, h.usage.input_tokens, h.usage.output_tokens), (2, 900, 240))

    def test_log_has_no_content(self):
        h = OpenAIVisionHandler(client=FakeClient("비밀 문서 내용"))
        with self.assertLogs("vision.openai_handler", logging.INFO) as cm:
            h(_req(VisionTask.DESCRIBE))
        line = "\n".join(cm.output)
        self.assertIn("describe", line)
        self.assertNotIn("비밀 문서 내용", line)


class RunnerIntegrationTest(unittest.TestCase):
    def test_errors_become_document_warnings(self):
        runner = VisionRunner(OpenAIVisionHandler(client=FakeClient("표 아님")))
        self.assertIsNone(runner.run(_req()))
        runner.close()
        self.assertTrue(any("VisionFormatError" in w for w in runner.warnings))

    def test_api_error_becomes_warning(self):
        runner = VisionRunner(OpenAIVisionHandler(client=FakeClient(error=RuntimeError("429 한도 초과"))))
        self.assertIsNone(runner.run(_req()))
        runner.close()
        self.assertTrue(any("429" in w for w in runner.warnings))


class FromEnvTest(unittest.TestCase):
    def test_no_key_returns_none(self):
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": " "}):
            self.assertIsNone(from_env())
        with mock.patch.dict(os.environ, {}, clear=True):
            self.assertIsNone(from_env())

    def test_key_makes_handler_without_network(self):
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "sk-test-not-real"}):
            h = from_env(model="gpt-6-luna")
        self.assertIsInstance(h, OpenAIVisionHandler)
        self.assertEqual(h.model, "gpt-6-luna")
        self.assertNotIn("sk-test-not-real", repr(vars(h)))   # 키를 속성으로 들고 있지 않음

    def test_handler_requires_key(self):
        with self.assertRaises(ValueError):
            OpenAIVisionHandler(api_key="")


if __name__ == "__main__":
    unittest.main()
