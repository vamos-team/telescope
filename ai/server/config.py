from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # backend가 X-Internal-Api-Key 헤더로 보내는 공유 비밀키
    internal_api_key: str = Field(min_length=1)
    # 작업 상태 SQLite 파일 경로. git에 올라가지 않도록 data/ 아래를 가리킨다
    db_path: str = Field(min_length=1)
    # 동시에 실행하는 파이프라인 수(워커 수)
    max_concurrent_jobs: int = Field(default=2, ge=1)
    # 실제 파이프라인이 붙기 전까지 쓰는 가짜 구현의 동작
    fake_pipeline_mode: Literal["success", "fail"] = "success"
    fake_pipeline_delay_seconds: float = Field(default=3.0, ge=0)
    log_level: str = "INFO"
