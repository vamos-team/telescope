from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    internal_api_key: str
    max_concurrent_jobs: int = 2
    db_path: str = "jobs.db"
    log_level: str = "INFO"


settings = Settings()
