"""OpenAI 비전 모델로 동작하는 VLM 처리기.

parsers는 네트워크에 접근하지 않는다. 이 처리기를 만들어 `parse(..., vision=처리기)`로 넘긴다.

    from vision import from_env
    doc = parse("upload.pdf", vision=from_env())   # 키가 없으면 None → needs_vision 표시만

- Responses API, store=False. 처리기가 받는 것은 라우팅이 고른 영역의 이미지뿐이다.
- 차트→표, 쪽 옮기기는 JSON 스키마(구조화 출력)로 받는다. 형식이 맞지 않으면 예외를 던진다.
  예외는 parsers의 VisionRunner가 문서 warnings에 남기고 그 영역을 needs_vision으로 둔다.
- 요청 한도 초과(429), 연결 오류, 5xx는 SDK가 재시도한다(max_retries).
- 로그에는 작업, 쪽, 모델, 토큰 수, 걸린 시간만 남긴다. API 키와 문서 내용은 남기지 않는다.
"""
from __future__ import annotations

import base64
import json
import logging
import os
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Optional

from parsers.vision import VisionRequest, VisionResult, VisionTask

log = logging.getLogger(__name__)

# #65 측정: 차트 12개 정답 76/76, 지어낸 숫자 0, 숫자와 칸의 짝 12/12, 설명 6/6
DEFAULT_MODEL = "gpt-6.1-terra"
MAX_DESCRIPTION_CHARS = 500


class VisionFormatError(ValueError):
    """모델 답이 정해진 형식이 아니다."""


# ─── 작업별 지시문과 출력 형식 ─────────────────────────────────

_RULES = (
    "- 이미지에 글자로 보이는 숫자만 그대로 적는다. 쉼표, 소수점, 부호, % 기호를 유지한다.\n"
    "- 막대 높이나 선 위치로 값을 추정하지 않는다. 글자로 보이지 않는 값은 빈 문자열로 둔다.\n"
    "- 이미지에 없는 내용을 덧붙이지 않는다."
)

PROMPT_TO_TABLE = (
    "이 이미지는 기업 자료의 차트 또는 표다. 표시된 모든 데이터 값을 표로 옮겨라.\n"
    "- 첫 행은 머리글(예: 기간, 계열 이름)이다. 증감률 열은 '<계열 이름> QoQ'처럼 어느 계열의 값인지 적는다.\n"
    + _RULES
)
PROMPT_PAGE = (
    "이 이미지는 기업 문서의 한 쪽이다. 쪽의 내용을 읽는 순서대로 옮겨라.\n"
    "- 문장과 제목은 paragraphs에, 표와 차트의 값은 tables에 행 단위로 넣는다. 표의 첫 행은 머리글이다.\n"
    + _RULES
)
PROMPT_DESCRIBE = (
    "이 이미지는 기업 자료 속 그림이다. 검색에 쓸 수 있게 무엇을 보여 주는지 한국어 1~2문장으로 설명하라.\n"
    "- 이미지에 글자로 보이지 않는 숫자나 이름은 쓰지 않는다."
)

_ROWS = {"type": "array", "items": {"type": "array", "items": {"type": "string"}}}
SCHEMA_TO_TABLE = {"type": "object", "properties": {"rows": _ROWS},
                   "required": ["rows"], "additionalProperties": False}
SCHEMA_PAGE = {"type": "object",
               "properties": {"paragraphs": {"type": "array", "items": {"type": "string"}},
                              "tables": {"type": "array", "items": _ROWS}},
               "required": ["paragraphs", "tables"], "additionalProperties": False}


def _clean_rows(rows: Any) -> list[list[str]]:
    if not isinstance(rows, list) or not all(isinstance(r, list) for r in rows):
        raise VisionFormatError("표가 행 목록이 아님")
    out = [[str(c).strip() for c in r] for r in rows]
    return [r for r in out if any(r)]


def _load_json(text: str) -> dict:
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise VisionFormatError(f"JSON이 아님: {e.msg}") from None
    if not isinstance(data, dict):
        raise VisionFormatError("JSON 객체가 아님")
    return data


def parse_to_table(text: str) -> VisionResult:
    rows = _clean_rows(_load_json(text).get("rows"))
    if len(rows) < 2:
        raise VisionFormatError("표에 머리글 외 행이 없음")
    return VisionResult(tables=[rows])


def parse_page(text: str) -> VisionResult:
    data = _load_json(text)
    paras = data.get("paragraphs")
    tables = data.get("tables")
    if not isinstance(paras, list) or not isinstance(tables, list):
        raise VisionFormatError("paragraphs 또는 tables가 목록이 아님")
    paras = [str(p).strip() for p in paras if str(p).strip()]
    tables = [t for t in (_clean_rows(t) for t in tables) if t]
    if not paras and not tables:
        raise VisionFormatError("옮긴 내용이 없음")
    return VisionResult(paragraphs=paras, tables=tables)


def parse_describe(text: str) -> VisionResult:
    t = " ".join((text or "").split())
    if not t:
        raise VisionFormatError("설명이 비어 있음")
    return VisionResult(description=t[:MAX_DESCRIPTION_CHARS])


@dataclass(frozen=True)
class TaskSpec:
    prompt: str
    schema: Optional[dict]
    max_output_tokens: int
    parse: Callable[[str], VisionResult]


TASKS: dict[VisionTask, TaskSpec] = {
    VisionTask.TO_TABLE: TaskSpec(PROMPT_TO_TABLE, SCHEMA_TO_TABLE, 4000, parse_to_table),
    VisionTask.PAGE: TaskSpec(PROMPT_PAGE, SCHEMA_PAGE, 8000, parse_page),
    VisionTask.DESCRIBE: TaskSpec(PROMPT_DESCRIBE, None, 1000, parse_describe),
}


# ─── 처리기 ─────────────────────────────────────────────────

@dataclass
class Usage:
    calls: int = 0
    input_tokens: int = 0
    output_tokens: int = 0


class OpenAIVisionHandler:
    """VisionHandler 구현. 인스턴스 하나를 여러 문서에 다시 써도 된다(usage는 누적)."""

    def __init__(self, api_key: Optional[str] = None, *, model: str = DEFAULT_MODEL,
                 timeout: float = 60.0, max_retries: int = 3, client: Any = None):
        if client is None:
            from openai import OpenAI
            if not api_key:
                raise ValueError("OpenAI API 키가 없음")
            client = OpenAI(api_key=api_key, timeout=timeout, max_retries=max_retries)
        self.client = client
        self.model = model
        self.usage = Usage()
        self._lock = threading.Lock()

    def build_request(self, req: VisionRequest) -> dict:
        spec = TASKS[req.task]
        image = "data:image/png;base64," + base64.b64encode(req.image_png).decode()
        body: dict = {
            "model": self.model,
            "store": False,
            "input": [{"role": "user", "content": [
                {"type": "input_text", "text": spec.prompt},
                {"type": "input_image", "image_url": image, "detail": "high"},
            ]}],
            "max_output_tokens": spec.max_output_tokens,
        }
        if spec.schema is not None:
            body["text"] = {"format": {"type": "json_schema", "name": f"vision_{req.task.value}",
                                       "strict": True, "schema": spec.schema}}
        return body

    def __call__(self, req: VisionRequest) -> VisionResult:
        spec = TASKS[req.task]
        t0 = time.perf_counter()
        resp = self.client.responses.create(**self.build_request(req))
        secs = time.perf_counter() - t0
        u = getattr(resp, "usage", None)
        tin, tout = getattr(u, "input_tokens", 0) or 0, getattr(u, "output_tokens", 0) or 0
        with self._lock:
            self.usage.calls += 1
            self.usage.input_tokens += tin
            self.usage.output_tokens += tout
        log.info("vision %s p%s model=%s in=%d out=%d %.1fs", req.task.value, req.page,
                 self.model, tin, tout, secs)
        status = getattr(resp, "status", "completed")
        if status != "completed":
            reason = getattr(getattr(resp, "incomplete_details", None), "reason", "") or ""
            raise VisionFormatError(f"응답이 끝나지 않음: {status} {reason}".strip())
        return spec.parse(resp.output_text or "")


def from_env(**kwargs) -> Optional[OpenAIVisionHandler]:
    """환경 변수 OPENAI_API_KEY로 처리기를 만든다. 키가 없으면 None (VLM 없이 진행)."""
    key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not key:
        return None
    return OpenAIVisionHandler(key, **kwargs)
