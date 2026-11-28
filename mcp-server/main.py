import logging
from contextlib import asynccontextmanager
from datetime import date

from fastapi import Depends, FastAPI, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from auth import verify_internal_api_key
from config import Settings
from stores import ChromaStore, DuckDbStore, ReadOnlyQueryUnavailable

logger = logging.getLogger(__name__)


# 조회에는 기준 시점과 허용 문서 범위가 필수다. 서버가 강제하고, 비어도 전체로 되돌아가지 않는다
# (루트 AGENTS.md 4장: 허용 문서 범위와 기준 시점 필터는 서버가 강제한다).
class DocumentSearchRequest(BaseModel):
    query: str = Field(min_length=1)
    as_of_date: date
    allowed_document_ids: list[str] = Field(min_length=1)


class FinancialQueryRequest(BaseModel):
    as_of_date: date
    company: str = Field(min_length=1)


def create_app(settings: Settings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        resolved = settings or Settings()
        app.state.settings = resolved
        app.state.duckdb = DuckDbStore(resolved.s3_bucket, resolved.aws_region)
        app.state.chroma = ChromaStore(resolved.chroma_url)
        yield

    app = FastAPI(lifespan=lifespan)

    @app.exception_handler(ReadOnlyQueryUnavailable)
    async def not_implemented(_, exc: ReadOnlyQueryUnavailable) -> JSONResponse:
        return JSONResponse(status_code=status.HTTP_501_NOT_IMPLEMENTED, content={"detail": str(exc)})

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/mcp/v1/documents/search", dependencies=[Depends(verify_internal_api_key)])
    def search_documents(request: DocumentSearchRequest) -> dict:
        results = app.state.chroma.search(request.query, request.as_of_date, request.allowed_document_ids)
        return {"results": results}

    @app.post("/mcp/v1/financial/query", dependencies=[Depends(verify_internal_api_key)])
    def query_financial(request: FinancialQueryRequest) -> dict:
        rows = app.state.duckdb.query_financial(request.as_of_date, request.company)
        return {"rows": rows}

    return app


app = create_app()
