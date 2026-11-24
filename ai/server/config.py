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
    # 종료 시 실행 중인 작업을 기다리는 최대 시간. 넘기면 FAILED로 기록한다
    shutdown_timeout_seconds: float = Field(default=20.0, ge=0)
    # 저장소 호출 실패 시 즉시 재시도 횟수(첫 시도 포함)와 첫 대기 시간(지수 증가)
    store_retry_attempts: int = Field(default=3, ge=1)
    store_retry_delay_seconds: float = Field(default=0.5, ge=0)
    # 최종 상태 저장에 끝내 실패한 작업을 다시 저장해 보는 주기
    pending_retry_interval_seconds: float = Field(default=5.0, gt=0)
    log_level: str = "INFO"
