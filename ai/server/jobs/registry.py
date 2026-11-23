import time
from typing import Any

from jobs.models import JobType
from jobs.pipeline import JobContext, PipelineError, PipelineFn


class PipelineRegistry:
    """작업 종류 → 파이프라인 함수 매핑. ai 담당 코드는 register로 붙인다."""

    def __init__(self) -> None:
        self._pipelines: dict[JobType, PipelineFn] = {}

    def register(self, job_type: JobType, fn: PipelineFn) -> None:
        self._pipelines[job_type] = fn

    def get(self, job_type: JobType) -> PipelineFn:
        return self._pipelines[job_type]


def make_fake_pipeline(mode: str, delay_seconds: float) -> PipelineFn:
    """실제 파이프라인이 붙기 전까지 쓰는 가짜 구현. 지연 후 성공 또는 실패한다."""

    def fake(ctx: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
        time.sleep(delay_seconds)
        if mode == "fail":
            raise PipelineError("가짜 파이프라인 실패")
        return {"fake": True}

    return fake


def build_fake_registry(mode: str, delay_seconds: float) -> PipelineRegistry:
    registry = PipelineRegistry()
    for job_type in JobType:
        registry.register(job_type, make_fake_pipeline(mode, delay_seconds))
    return registry
