"""파이프라인(ai 담당 코드)이 따르는 인터페이스.

파이프라인 함수는 `(ctx, payload) -> result` 모양의 동기 함수다.
- ctx: 서버가 요청에서 꺼내 고정한 값(기준 시점, 허용 문서). 수정할 수 없다.
- payload: 작업별 나머지 요청 필드. 기준 시점·허용 문서는 들어 있지 않다(ctx로만 받는다).
- result: JSON으로 직렬화 가능한 dict. 작업별 모양은 작업별 입출력 이슈에서 정한다.

실패 사유를 backend(와 사용자)에게 보여주려면 PipelineError를 던진다. 그 밖의 예외는
내부 정보 노출을 막기 위해 일반 문구로 바뀌고, 상세 내용은 서버 로그에만 남는다.
"""

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date
from typing import Any


@dataclass(frozen=True)
class JobContext:
    job_id: str
    # 기준 시점. 이 시점 이후 발행된 자료는 쓰지 않는다. 자료 준비 작업은 None
    as_of_date: date | None
    # 허용 문서 버전 ID. None이면 이 작업 종류에 허용 목록이 없다는 뜻이다(전체 허용이 아니다)
    allowed_document_version_ids: frozenset[str] | None


class PipelineError(Exception):
    """사용자에게 보여도 되는 실패 사유를 담는 예외.

    user_message에는 스택트레이스, 내부 경로, 문서 원문, 모델명·프롬프트를 넣지 않는다.
    """

    def __init__(self, user_message: str) -> None:
        super().__init__(user_message)
        self.user_message = user_message


PipelineFn = Callable[[JobContext, dict[str, Any]], dict[str, Any]]
