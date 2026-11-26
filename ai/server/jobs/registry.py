import importlib
import time
from typing import TYPE_CHECKING, Any

from jobs.files import FileSource, LocalFileSource, S3FileSource, with_staged_file
from jobs.models import JobType
from jobs.pipeline import JobContext, PipelineError, PipelineFn

if TYPE_CHECKING:
    from config import Settings


class PipelineRegistry:
    """작업 종류 → 파이프라인 함수 매핑. ai 담당 코드는 register로 붙인다."""

    def __init__(self) -> None:
        self._pipelines: dict[JobType, PipelineFn] = {}

    def register(self, job_type: JobType, fn: PipelineFn) -> None:
        """작업 종류에 파이프라인 함수를 붙인다. 같은 종류를 다시 붙이면 덮어쓴다."""
        self._pipelines[job_type] = fn

    def get(self, job_type: JobType) -> PipelineFn:
        """작업 종류의 파이프라인 함수를 돌려준다. 없으면 KeyError."""
        return self._pipelines[job_type]

    def supports(self, job_type: JobType) -> bool:
        """이 작업 종류에 파이프라인이 붙어 있는지. 없으면 시작 요청을 거절한다."""
        return job_type in self._pipelines


def make_fake_pipeline(mode: str, delay_seconds: float) -> PipelineFn:
    """실제 파이프라인이 붙기 전까지 쓰는 가짜 구현. 지연 후 성공 또는 실패한다."""

    def fake(ctx: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
        time.sleep(delay_seconds)
        if mode == "fail":
            raise PipelineError("가짜 파이프라인 실패")
        return {"fake": True}

    return fake


def build_fake_registry(mode: str, delay_seconds: float) -> PipelineRegistry:
    """모든 작업 종류에 가짜 구현을 붙인다. 로컬 개발·화면 연동용(PIPELINE_MODE=fake)."""
    registry = PipelineRegistry()
    for job_type in JobType:
        registry.register(job_type, make_fake_pipeline(mode, delay_seconds))
    return registry


def load_entrypoint(spec: str) -> Any:
    """"모듈경로:함수명" 문자열로 ai 담당 등록 함수를 불러온다. 형식이 틀리거나 없으면 기동을 멈춘다."""
    module_name, sep, attr = spec.partition(":")
    if not sep or not module_name or not attr:
        raise ValueError(f"PIPELINE_ENTRYPOINT 형식은 '모듈경로:함수명'이어야 합니다: {spec!r}")
    module = importlib.import_module(module_name)
    try:
        return getattr(module, attr)
    except AttributeError as e:
        raise ValueError(f"PIPELINE_ENTRYPOINT의 함수를 찾을 수 없습니다: {spec!r}") from e


def build_file_source(settings: "Settings") -> FileSource:
    """원본 파일 위치를 고른다. S3_BUCKET이 있으면 S3(자격증명은 기본 체인), 없으면 로컬 폴더."""
    if settings.s3_bucket:
        import boto3

        return S3FileSource(boto3.client("s3", region_name=settings.aws_region), settings.s3_bucket)
    return LocalFileSource(settings.file_storage_dir)


def build_real_registry(settings: "Settings", file_source: FileSource | None = None) -> PipelineRegistry:
    """ai 담당 등록 함수를 불러 실제 파이프라인을 붙인다(PIPELINE_MODE=real).

    - 등록 함수는 `register(registry)` 모양이고, 구현한 작업 종류만 register한다.
    - 자료 준비는 서버가 원본을 로컬로 준비해 payload의 `file_path`로 넘기도록 감싼다
      (ai 코드는 S3·로컬 구분 없이 경로만 읽는다).
    - 등록되지 않은 작업 종류는 가짜로 채우지 않는다. 시작 요청을 501로 거절한다.
      TODO(#86 후속): 분석·예측 파이프라인(#66 등)이 생기면 ai 등록 함수에 추가되고, 그때 거절이 자연히 풀린다.
      배포에서 가짜 결과가 실제처럼 저장되는 일을 막으려고 섞어 쓰지 않는다.
    """
    registry = PipelineRegistry()
    load_entrypoint(settings.pipeline_entrypoint)(registry)
    if registry.supports(JobType.DOCUMENT_PREPARATION):
        source = file_source or build_file_source(settings)
        registry.register(
            JobType.DOCUMENT_PREPARATION,
            with_staged_file(registry.get(JobType.DOCUMENT_PREPARATION), source),
        )
    return registry


def build_registry(settings: "Settings") -> PipelineRegistry:
    """설정(PIPELINE_MODE)에 따라 가짜 또는 실제 파이프라인 등록부를 만든다."""
    if settings.pipeline_mode == "real":
        return build_real_registry(settings)
    return build_fake_registry(settings.fake_pipeline_mode, settings.fake_pipeline_delay_seconds)
