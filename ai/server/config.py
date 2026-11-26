from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    # 같은 .env에 ai 파이프라인 코드가 직접 읽는 키(OPENAI_API_KEY, PARSER_EMBED_MODEL, HF_TOKEN 등)가 함께 있을 수 있다.
    # 서버가 선언하지 않은 키는 무시한다(기본값 forbid면 값이 채워지는 순간 기동이 실패한다).
    # 선언한 필드의 검증은 그대로다. 대신 서버 키의 오타도 조용히 무시되므로 이름은 .env.example을 따른다.
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # backend가 X-Internal-Api-Key 헤더로 보내는 공유 비밀키
    internal_api_key: str = Field(min_length=1)
    # 작업 상태 SQLite 파일 경로. git에 올라가지 않도록 data/ 아래를 가리킨다
    db_path: str = Field(min_length=1)
    # 동시에 실행하는 파이프라인 수(워커 수).
    # TODO(#86 후속): 실제 자료 준비 파이프라인(Docling·임베딩 모델 동시 로드)이 붙으면 메모리 기준으로 기본값을 다시 정한다
    max_concurrent_jobs: int = Field(default=2, ge=1)

    # fake: 모든 작업 종류에 가짜 구현. real: pipeline_entrypoint가 등록한 작업만 받는다
    pipeline_mode: Literal["fake", "real"] = "fake"
    # real 모드에서 불러올 ai 담당 등록 함수. "모듈경로:함수명" (예: "ai.pipelines:register").
    # 함수는 register(registry: PipelineRegistry) -> None 모양이다
    pipeline_entrypoint: str | None = None

    # 자료 준비 원본 위치. S3_BUCKET이 있으면 S3(배포), 없으면 file_storage_dir(로컬).
    # file_storage_dir는 backend의 FILE_STORAGE_DIR과 같은 폴더를 가리켜야 한다. 실행 위치마다 상대 경로가 달라
    # 기본값을 두지 않는다
    s3_bucket: str | None = None
    aws_region: str | None = None
    file_storage_dir: str | None = None

    # 실제 파이프라인이 붙기 전까지 쓰는 가짜 구현의 동작
    fake_pipeline_mode: Literal["success", "fail"] = "success"
    fake_pipeline_delay_seconds: float = Field(default=3.0, ge=0)
    # 종료 시 실행 중인 작업을 기다리는 최대 시간. 넘기면 FAILED로 기록한다
    shutdown_timeout_seconds: float = Field(default=20.0, ge=0)
    # 저장소 호출 실패 시 즉시 재시도 횟수(첫 시도 포함)와 첫 대기 시간(지수 증가)
    store_retry_attempts: int = Field(default=3, ge=1)
    store_retry_delay_seconds: float = Field(default=0.5, ge=0)
    # 최종 상태 저장에 끝내 실패한 작업을 다시 저장해 보는 주기
    pending_retry_interval_seconds: float = Field(default=5.0, gt=0)
    log_level: str = "INFO"

    @model_validator(mode="after")
    def _check_pipeline_settings(self) -> "Settings":
        """잘못된 조합을 기동 시점에 막는다. 빈 문자열은 값이 없는 것으로 본다."""
        self.pipeline_entrypoint = self.pipeline_entrypoint or None
        self.s3_bucket = self.s3_bucket or None
        self.aws_region = self.aws_region or None
        self.file_storage_dir = self.file_storage_dir or None
        if self.pipeline_mode == "real" and self.pipeline_entrypoint is None:
            raise ValueError("PIPELINE_MODE=real이면 PIPELINE_ENTRYPOINT가 필요합니다.")
        if self.s3_bucket is not None and self.aws_region is None:
            raise ValueError("S3_BUCKET이 설정됐지만 AWS_REGION이 비어 있습니다.")
        if self.pipeline_mode == "real" and self.s3_bucket is None and self.file_storage_dir is None:
            raise ValueError("PIPELINE_MODE=real이면 S3_BUCKET 또는 FILE_STORAGE_DIR이 필요합니다.")
        return self
