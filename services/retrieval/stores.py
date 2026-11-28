"""조회 대상(DuckDB·Chroma) 연결. 읽기 전용이다. 실제 조회 로직은 이후 ai 담당이 채운다(#194는 뼈대)."""
from __future__ import annotations

from datetime import date


class ReadOnlyQueryUnavailable(RuntimeError):
    """조회 로직이 아직 붙지 않았을 때. main이 501로 바꾼다."""


class DuckDbStore:
    """재무 수치 DuckDB 판. S3에서 받아 읽기 전용(read_only=True)으로 연다. mcp-server는 쓰지 않는다."""

    def __init__(self, s3_bucket: str | None, aws_region: str | None) -> None:
        self._s3_bucket = s3_bucket
        self._aws_region = aws_region

    def query_financial(self, as_of_date: date, company: str) -> list[dict]:
        # TODO(ai 담당): duckdb_path(S3 키)의 판을 /tmp로 받아 duckdb.connect(..., read_only=True)로 조회.
        # 기준 시점(as_of_date) 이전 발행 자료만. 숫자 단위 변환은 코드에서 처리한다(루트 AGENTS.md 4장).
        raise ReadOnlyQueryUnavailable("DuckDB 조회는 아직 구현되지 않았습니다(#194 뼈대).")


class ChromaStore:
    """문서 검색 Chroma. 읽기만 한다(적재는 자료 준비 ingest가 한다)."""

    def __init__(self, chroma_url: str | None) -> None:
        self._chroma_url = chroma_url

    def search(self, query: str, as_of_date: date, allowed_document_ids: list[str]) -> list[dict]:
        # TODO(ai 담당): chromadb.HttpClient로 연결해 조회. 허용 문서(allowed_document_ids)와
        # 기준 시점(as_of_date) 필터를 where로 건다. 결과가 비어도 전체 문서로 되돌아가지 않는다.
        raise ReadOnlyQueryUnavailable("Chroma 조회는 아직 구현되지 않았습니다(#194 뼈대).")
