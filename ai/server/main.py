import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, status
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from config import Settings
from db import init_db
from jobs.registry import PipelineRegistry, build_registry
from jobs.repository import JobRepository
from jobs.router import router as jobs_router
from jobs.service import JobService, UnsupportedJobTypeError
from jobs.worker import JobWorker, WorkerUnavailableError

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None, registry: PipelineRegistry | None = None) -> FastAPI:
    """settings·registry는 테스트에서 주입한다. 생략하면 환경변수와 설정(PIPELINE_MODE)에 맞는 파이프라인을 쓴다."""

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        resolved = settings or Settings()
        logging.basicConfig(level=resolved.log_level)

        init_db(resolved.db_path)
        repository = JobRepository(resolved.db_path)
        worker = JobWorker(
            repository,
            registry or build_registry(resolved),
            resolved.max_concurrent_jobs,
            shutdown_timeout=resolved.shutdown_timeout_seconds,
            store_retry_attempts=resolved.store_retry_attempts,
            store_retry_delay=resolved.store_retry_delay_seconds,
            pending_retry_interval=resolved.pending_retry_interval_seconds,
        )
        service = JobService(repository, worker)
        service.fail_interrupted_jobs()

        app.state.settings = resolved
        app.state.worker = worker
        app.state.job_service = service
        worker.start()
        try:
            yield
        finally:
            await worker.stop()

    app = FastAPI(title="telescope-ai-server", lifespan=lifespan)
    app.include_router(jobs_router)

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        # 기본 응답은 요청 입력값(input)을 그대로 되돌려준다. 위치·사유만 남긴다
        errors = [{"loc": e.get("loc"), "type": e.get("type"), "msg": e.get("msg")} for e in exc.errors()]
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            content=jsonable_encoder({"detail": errors}),
        )

    @app.exception_handler(WorkerUnavailableError)
    async def worker_unavailable_handler(request: Request, exc: WorkerUnavailableError) -> JSONResponse:
        return JSONResponse(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            content={"detail": "서버가 작업을 받을 수 없는 상태입니다."},
        )

    @app.exception_handler(UnsupportedJobTypeError)
    async def unsupported_job_type_handler(request: Request, exc: UnsupportedJobTypeError) -> JSONResponse:
        # backend는 시작 호출의 4xx/5xx를 모두 markFailed로 끝낸다(ai-contract 2.5)
        return JSONResponse(
            status_code=status.HTTP_501_NOT_IMPLEMENTED,
            content={"detail": "아직 지원하지 않는 작업입니다."},
        )

    @app.exception_handler(Exception)
    async def unhandled_error_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.exception("처리하지 못한 오류: %s %s", request.method, request.url.path)
        return JSONResponse(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            content={"detail": "서버 내부 오류가 발생했습니다."},
        )

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    return app


app = create_app()
