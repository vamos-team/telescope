from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # backend·ai 서비스가 X-Internal-Api-Key 헤더로 보내는 공유 비밀키
    internal_api_key: str = Field(min_length=1)

    # 조회 대상. DuckDB 판은 S3에서 받아 읽기 전용으로 연다(데이터 파이프라인이 S3에 올린다).
    s3_bucket: str | None = None
    aws_region: str | None = None
    # 문서 검색용 Chroma 주소(예: http://chroma:8000)
    chroma_url: str | None = None

    @model_validator(mode="after")
    def check(self) -> "Settings":
        if self.s3_bucket and not self.aws_region:
            raise ValueError("S3_BUCKET이 설정됐지만 AWS_REGION이 비어 있습니다.")
        return self
