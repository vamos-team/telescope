"""VLM 처리기 인터페이스와 PDF 그림·쪽 라우팅 규칙.

경로는 코드가 정한다(쪽의 텍스트 층 글자 수, 그림 종류, 그림 영역 안 글자 유무).
VLM은 이 규칙이 고른 영역만 받는다. VLM에게 경로 선택을 맡기지 않는다.

처리기 연결 예:

    def my_vlm(req: VisionRequest) -> VisionResult:
        ...                                  # 로컬 모델 호출. 외부 API로 보내지 않는다
        return VisionResult(tables=[[["구분", "2Q26"], ["매출", "1,234"]]])

    parse("upload.pdf", vision=my_vlm)

처리기가 없으면 해당 블록에 `needs_vision` 표시만 남기고 계속 진행한다.
처리기 결과는 `origin="vision_llm"`으로 표시되어 코드가 읽은 값과 섞이지 않는다.
"""
from __future__ import annotations

import hashlib
import re
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as _FutureTimeout
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Optional


class VisionTask(str, Enum):
    PAGE = "page"            # 글자 없는 쪽 전체 → 문단·표
    TO_TABLE = "to_table"    # 글자 없는 차트·표 이미지 → 표
    DESCRIBE = "describe"    # 사진·도식 → 검색용 설명


@dataclass
class VisionRequest:
    task: VisionTask
    image_png: bytes
    page: int
    bbox: Optional[tuple[float, float, float, float]] = None   # 쪽 안 위치(pt, 왼쪽 위 기준). 쪽 전체면 None
    picture_class: str = ""                                    # 그림 종류(분류 모델 결과). 쪽 전체면 ""


@dataclass
class VisionResult:
    paragraphs: list[str] = field(default_factory=list)
    tables: list[list[list[str]]] = field(default_factory=list)
    description: str = ""


VisionHandler = Callable[[VisionRequest], Optional[VisionResult]]

# ─── 라우팅 규칙 ─────────────────────────────────────────────

# 글자가 적은 쪽에서, 텍스트 층 글자와 로고·아이콘을 가린 뒤 남은 윤곽(이웃 픽셀 밝기 차) 비율이
# 이 값 이상이면 읽지 못한 내용이 있다고 본다. 측정: 간지·로고 쪽 0.0~0.2%, 스캔한 내용 쪽 3.3~17.8%
MIN_PAGE_EDGES = 0.005

# 문서 하나에서 VLM 처리기를 부르는 최대 횟수(비용 상한). 같은 이미지 재사용(캐시)은 세지 않는다
VISION_MAX_CALLS = 30

# 쪽의 텍스트 층 글자 수가 이보다 적으면 "글자 없는 쪽"으로 본다 (기존 pdfplumber 기준과 같음)
MIN_PAGE_CHARS = 15

# 그림 종류(DocumentFigureClassifier 라벨) → 경로
CHART_CLASSES = frozenset({"bar_chart", "line_chart", "pie_chart", "scatter_plot", "box_plot", "table"})
DECOR_CLASSES = frozenset({"logo", "icon", "signature", "stamp", "qr_code", "bar_code", "page_thumbnail"})


class PictureRoute(str, Enum):
    CHART_TEXT = "chart_text"        # 차트 + 영역 안 글자 있음 → 텍스트 층 글자를 차트 블록으로
    CHART_VISION = "chart_vision"    # 차트 + 영역 안 글자 없음 → VLM [이미지→표]
    DECOR = "decor"                  # 로고·아이콘 → 이미지는 버리고 글자만
    DESCRIBE = "describe"            # 사진·도식 → 글자는 문단으로, VLM [설명 생성]


class PageRoute(str, Enum):
    TEXT = "text"        # 텍스트 층 글자 있음 → Docling
    VISION = "vision"    # 글자 없음 + 내용 그림 있음 → VLM [쪽 옮기기]
    DECOR = "decor"      # 글자 없음 + 로고·아이콘뿐 (표지·간지) → VLM 안 씀
    EMPTY = "empty"      # 글자도 그림도 없음


def route_page(text_chars: int, picture_classes: list[str], unread_content: bool = False) -> PageRoute:
    """unread_content: 텍스트 층으로 설명되지 않는 내용이 쪽에 보인다(스캔한 글자·표 등).

    Docling은 OCR 없이 읽지 못한 글자 영역을 결과에서 뺀다. 그래서 스캔 쪽은 그림이 없으면
    빈 쪽처럼 보인다. pdf.py가 쪽 이미지의 윤곽 밀도로 이 경우를 따로 알려 준다.
    """
    if text_chars >= MIN_PAGE_CHARS:
        return PageRoute.TEXT
    if unread_content:
        return PageRoute.VISION
    if not picture_classes:
        return PageRoute.EMPTY
    if all(c in DECOR_CLASSES for c in picture_classes):
        return PageRoute.DECOR
    return PageRoute.VISION


# 차트 값이 아닌 숫자: 기간(2Q25, 25.2Q, 1H26), 연도(2025, 2025년), 월(3월)
_NOT_VALUE = re.compile(
    r"(?i)(?<![\d.,])(?:[1-4]\s*Q\s*'?\d{2,4}|'?\d{2,4}\s*\.?\s*[1-4]\s*Q|[12]\s*H\s*'?\d{2,4}"
    r"|(?:19|20)\d{2}\s*년?|\d{1,2}\s*월)(?![\d.,%])")


def has_value_numbers(texts: list[str]) -> bool:
    """차트 영역 글자에 값으로 볼 숫자가 있으면 True. 단위·범례·기간 글자만 있으면 False."""
    return any(re.search(r"\d", _NOT_VALUE.sub(" ", t or "")) for t in texts)


def route_picture(picture_class: str, inner_texts: list[str]) -> PictureRoute:
    if picture_class in DECOR_CLASSES:
        return PictureRoute.DECOR
    if picture_class in CHART_CLASSES:
        # 값 숫자가 텍스트 층에 있어야 코드로 읽는다. 단위('단위 : 원')나 범례만 있으면 VLM으로 보낸다
        return PictureRoute.CHART_TEXT if has_value_numbers(inner_texts) else PictureRoute.CHART_VISION
    return PictureRoute.DESCRIBE


# ─── 처리기 호출 (시간 제한, 같은 이미지 재처리 방지) ────────────

class VisionRunner:
    """문서 하나를 읽는 동안 VLM 처리기를 부른다.

    - 처리기가 None이면 항상 None을 돌려준다(호출부가 needs_vision 표시).
    - 같은 (작업, 이미지)는 한 번만 부른다. 문서 사이 캐시는 두지 않는다(저장 위치 미정).
    - timeout 초를 넘기면 None과 경고를 남긴다. 처리기 스레드는 강제로 멈추지 못하므로
      처리기 쪽에도 자체 시간 제한을 두어야 한다.
    """

    def __init__(self, handler: Optional[VisionHandler], timeout: float = 600.0,
                 max_calls: Optional[int] = None):
        self.handler = handler
        self.timeout = timeout
        self.max_calls = max_calls      # None이면 상한 없음
        self.calls = 0
        self.skipped = 0                # 상한을 넘어 부르지 않은 요청 수
        self.warnings: list[str] = []
        self._cache: dict[str, Optional[VisionResult]] = {}
        self._pool = ThreadPoolExecutor(max_workers=1) if handler else None

    @property
    def enabled(self) -> bool:
        return self.handler is not None

    def run(self, req: VisionRequest) -> Optional[VisionResult]:
        if self.handler is None:
            return None
        key = req.task.value + ":" + hashlib.sha256(req.image_png).hexdigest()
        if key in self._cache:
            return self._cache[key]
        if self.max_calls is not None and self.calls >= self.max_calls:
            self.skipped += 1
            return None
        self.calls += 1
        where = f"{req.page}쪽 {req.task.value}"
        res: Optional[VisionResult] = None
        try:
            out = self._pool.submit(self.handler, req).result(timeout=self.timeout)
            res = _coerce(out)
        except _FutureTimeout:
            self.warnings.append(f"{where}: VLM 시간 제한({self.timeout:.0f}초) 초과")
        except Exception as e:   # 처리기 오류로 문서 전체를 버리지 않는다
            self.warnings.append(f"{where}: VLM 실패: {type(e).__name__}: {e}")
        self._cache[key] = res
        return res

    def close(self) -> None:
        if self.skipped:
            self.warnings.append(f"VLM 호출 상한({self.max_calls}번) 초과: {self.skipped}개 영역은 "
                                 "처리하지 않음 (needs_vision)")
            self.skipped = 0
        if self._pool is not None:
            self._pool.shutdown(wait=False, cancel_futures=True)


def _coerce(out: object) -> Optional[VisionResult]:
    if out is None or isinstance(out, VisionResult):
        return out
    if isinstance(out, dict):    # {"paragraphs": [...], "tables": [...], "description": "..."}
        return VisionResult(paragraphs=[str(p) for p in out.get("paragraphs") or []],
                            tables=out.get("tables") or [],
                            description=str(out.get("description") or ""))
    raise TypeError(f"VLM 처리기 반환 형식이 잘못됨: {type(out).__name__}")
