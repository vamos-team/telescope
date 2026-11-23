import asyncio
import json
import logging
from dataclasses import dataclass
from typing import Any

from jobs.models import JobType
from jobs.pipeline import JobContext, PipelineError
from jobs.registry import PipelineRegistry
from jobs.repository import JobRepository

logger = logging.getLogger(__name__)

GENERIC_ERROR_MESSAGE = "작업 처리 중 오류가 발생했습니다."
MAX_ERROR_MESSAGE_LENGTH = 500


@dataclass(frozen=True)
class QueuedJob:
    job_type: JobType
    context: JobContext
    payload: dict[str, Any]


def _safe_message(message: str) -> str:
    message = message.strip()
    if not message:
        return GENERIC_ERROR_MESSAGE
    return message[:MAX_ERROR_MESSAGE_LENGTH]


class JobWorker:
    """in-process 큐 + 고정 개수 워커. 워커 수가 곧 동시 실행 상한이다.

    큐 길이는 제한하지 않는다(한도 초과 시 429 응답 여부는 ai-contract 2.8 열림).
    """

    def __init__(self, repository: JobRepository, registry: PipelineRegistry, concurrency: int) -> None:
        self._repository = repository
        self._registry = registry
        self._concurrency = concurrency
        self._queue: asyncio.Queue[QueuedJob] = asyncio.Queue()
        self._tasks: list[asyncio.Task] = []

    def start(self) -> None:
        self._tasks = [asyncio.create_task(self._run_forever()) for _ in range(self._concurrency)]

    async def stop(self) -> None:
        for task in self._tasks:
            task.cancel()
        await asyncio.gather(*self._tasks, return_exceptions=True)
        self._tasks = []

    def submit(self, job: QueuedJob) -> None:
        self._queue.put_nowait(job)

    async def _run_forever(self) -> None:
        while True:
            job = await self._queue.get()
            try:
                await self._execute(job)
            except Exception:
                # 저장소 오류 등. 워커는 계속 살아 있어야 한다
                logger.exception("작업 처리 중 예상하지 못한 오류: job_id=%s", job.context.job_id)
            finally:
                self._queue.task_done()

    async def _execute(self, job: QueuedJob) -> None:
        job_id = job.context.job_id
        if not self._repository.mark_running(job_id):
            return

        try:
            fn = self._registry.get(job.job_type)
            result = await asyncio.to_thread(fn, job.context, job.payload)
            result_json = json.dumps(result, ensure_ascii=False)
        except PipelineError as e:
            logger.warning("파이프라인 실패: job_id=%s", job_id)
            self._repository.mark_failed(job_id, _safe_message(e.user_message))
        except Exception:
            logger.exception("파이프라인 예외: job_id=%s", job_id)
            self._repository.mark_failed(job_id, GENERIC_ERROR_MESSAGE)
        else:
            self._repository.mark_succeeded(job_id, result_json)
