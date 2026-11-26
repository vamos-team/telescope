import asyncio
import json
import logging
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, TypeVar

from jobs.models import JobType
from jobs.pipeline import JobContext, PipelineError
from jobs.registry import PipelineRegistry
from jobs.repository import JobRepository

logger = logging.getLogger(__name__)

GENERIC_ERROR_MESSAGE = "작업 처리 중 오류가 발생했습니다."
START_ERROR_MESSAGE = "작업을 시작하지 못했습니다."
SHUTDOWN_ERROR_MESSAGE = "서버 종료로 작업이 중단되었습니다."
MAX_ERROR_MESSAGE_LENGTH = 500

T = TypeVar("T")


class WorkerUnavailableError(Exception):
    """종료 중이거나 시작 전이라 작업을 받을 수 없다."""


@dataclass(frozen=True)
class QueuedJob:
    job_type: JobType
    context: JobContext
    payload: dict[str, Any]


@dataclass(frozen=True)
class _PendingFinal:
    """파이프라인은 끝났지만 최종 상태 저장에 실패한 작업. 저장만 다시 시도한다."""

    job_id: str
    succeeded: bool
    value: str  # succeeded면 result_json, 아니면 error_message


def _safe_message(message: str) -> str:
    message = message.strip()
    if not message:
        return GENERIC_ERROR_MESSAGE
    return message[:MAX_ERROR_MESSAGE_LENGTH]


class JobWorker:
    """in-process 큐 + 고정 개수 워커. 워커 수가 곧 동시 실행 상한이다.

    - submit은 요청 스레드(동기 핸들러의 스레드 풀)에서 불리므로 이벤트 루프로 넘겨 큐에 넣는다.
    - 저장소 호출은 이벤트 루프를 막지 않도록 스레드에서 실행한다.
    - 실행 전 저장 실패는 파이프라인을 돌리지 않고 FAILED로 끝낸다. 실행 후 최종 저장 실패는
      결과를 보관해 저장만 다시 시도한다(파이프라인 재실행 없음).
    - 종료 시 새 작업을 받지 않고, 실행 중인 작업을 shutdown_timeout까지 기다린다.
      넘기면 FAILED로 기록한다. 큐에서 기다리던 작업은 QUEUED로 남아 재시작 시 FAILED가 된다.

    큐 길이는 제한하지 않는다(한도 초과 시 429 응답 여부는 ai-contract 2.8 열림).
    """

    def __init__(
        self,
        repository: JobRepository,
        registry: PipelineRegistry,
        concurrency: int,
        *,
        shutdown_timeout: float,
        store_retry_attempts: int,
        store_retry_delay: float,
        pending_retry_interval: float,
    ) -> None:
        self._repository = repository
        self._registry = registry
        self._concurrency = concurrency
        self._shutdown_timeout = shutdown_timeout
        self._store_retry_attempts = store_retry_attempts
        self._store_retry_delay = store_retry_delay
        self._pending_retry_interval = pending_retry_interval

        self._queue: asyncio.Queue[QueuedJob] = asyncio.Queue()
        self._loop: asyncio.AbstractEventLoop | None = None
        self._stopping: asyncio.Event | None = None
        self._accepting = False
        self._tasks: list[asyncio.Task] = []
        self._retry_task: asyncio.Task | None = None
        self._running: dict[asyncio.Task, str] = {}  # 워커 task → 실행 중인 job_id
        self._pending: dict[str, _PendingFinal] = {}

    @property
    def is_accepting(self) -> bool:
        return self._accepting

    def supports(self, job_type: JobType) -> bool:
        """이 작업 종류를 실행할 파이프라인이 등록돼 있는지."""
        return self._registry.supports(job_type)

    def start(self) -> None:
        self._loop = asyncio.get_running_loop()
        self._stopping = asyncio.Event()
        self._tasks = [asyncio.create_task(self._run_forever()) for _ in range(self._concurrency)]
        self._retry_task = asyncio.create_task(self._retry_pending_forever())
        self._accepting = True

    def submit(self, job: QueuedJob) -> None:
        """어느 스레드에서 불러도 된다."""
        if not self._accepting or self._loop is None:
            raise WorkerUnavailableError
        try:
            self._loop.call_soon_threadsafe(self._queue.put_nowait, job)
        except RuntimeError as e:  # 루프가 이미 닫혔다
            raise WorkerUnavailableError from e

    async def stop(self) -> None:
        self._accepting = False
        if self._stopping is not None:
            self._stopping.set()

        if self._tasks:
            _, unfinished = await asyncio.wait(self._tasks, timeout=self._shutdown_timeout)
            timed_out = [self._running[t] for t in unfinished if t in self._running]
            for task in unfinished:
                task.cancel()
            await asyncio.gather(*unfinished, return_exceptions=True)
            for job_id in timed_out:
                logger.warning("종료 대기 시간 초과로 작업을 중단했다: job_id=%s", job_id)
                await self._finalize(job_id, succeeded=False, value=SHUTDOWN_ERROR_MESSAGE)
        self._tasks = []

        if self._retry_task is not None:
            self._retry_task.cancel()
            await asyncio.gather(self._retry_task, return_exceptions=True)
            self._retry_task = None
        await self._flush_pending()
        if self._pending:
            # 저장하지 못한 작업은 RUNNING으로 남고, 재시작 시 FAILED로 정리된다
            logger.error("최종 상태를 저장하지 못한 채 종료한다: %d건", len(self._pending))

    async def _run_forever(self) -> None:
        while True:
            job = await self._next_job()
            if job is None:
                return
            task = asyncio.current_task()
            self._running[task] = job.context.job_id
            try:
                await self._execute(job)
            except Exception:
                logger.exception("작업 처리 중 예상하지 못한 오류: job_id=%s", job.context.job_id)
            finally:
                self._running.pop(task, None)
                self._queue.task_done()

    async def _next_job(self) -> QueuedJob | None:
        """다음 작업을 꺼낸다. 종료가 시작되면 None."""
        assert self._stopping is not None
        if self._stopping.is_set():
            return None
        get = asyncio.ensure_future(self._queue.get())
        stop = asyncio.ensure_future(self._stopping.wait())
        try:
            await asyncio.wait({get, stop}, return_when=asyncio.FIRST_COMPLETED)
        finally:
            stop.cancel()
            if not get.done():
                get.cancel()
        if get.cancelled() or not get.done():
            return None
        job = get.result()
        if self._stopping.is_set():
            # 종료 중에 꺼낸 작업은 시작하지 않는다. DB에 QUEUED로 남아 재시작 시 정리된다
            self._queue.task_done()
            return None
        return job

    async def _execute(self, job: QueuedJob) -> None:
        job_id = job.context.job_id
        try:
            started = await self._store(self._repository.mark_running, job_id)
        except Exception:
            logger.exception("작업 시작 상태 저장 실패: job_id=%s", job_id)
            await self._finalize(job_id, succeeded=False, value=START_ERROR_MESSAGE)
            return
        if not started:
            return

        try:
            fn = self._registry.get(job.job_type)
            result = await asyncio.to_thread(fn, job.context, job.payload)
            result_json = json.dumps(result, ensure_ascii=False)
        except PipelineError as e:
            logger.warning("파이프라인 실패: job_id=%s", job_id)
            await self._finalize(job_id, succeeded=False, value=_safe_message(e.user_message))
        except Exception:
            logger.exception("파이프라인 예외: job_id=%s", job_id)
            await self._finalize(job_id, succeeded=False, value=GENERIC_ERROR_MESSAGE)
        else:
            await self._finalize(job_id, succeeded=True, value=result_json)

    async def _finalize(self, job_id: str, succeeded: bool, value: str) -> None:
        fn = self._repository.mark_succeeded if succeeded else self._repository.mark_failed
        try:
            await self._store(fn, job_id, value)
        except Exception:
            logger.exception("최종 상태 저장 실패, 복구 재시도 대기: job_id=%s", job_id)
            self._pending[job_id] = _PendingFinal(job_id, succeeded, value)

    async def _store(self, fn: Callable[..., T], *args: Any) -> T:
        """저장소 호출을 스레드에서 실행하고, 실패하면 정해진 횟수만큼 다시 시도한다."""
        for attempt in range(1, self._store_retry_attempts + 1):
            try:
                return await asyncio.to_thread(fn, *args)
            except Exception:
                if attempt == self._store_retry_attempts:
                    raise
                logger.warning("저장소 호출 실패, 재시도 %d/%d", attempt, self._store_retry_attempts)
                await asyncio.sleep(self._store_retry_delay * 2 ** (attempt - 1))
        raise AssertionError("unreachable")

    async def _retry_pending_forever(self) -> None:
        while True:
            await asyncio.sleep(self._pending_retry_interval)
            await self._flush_pending()

    async def _flush_pending(self) -> None:
        for pending in list(self._pending.values()):
            fn = self._repository.mark_succeeded if pending.succeeded else self._repository.mark_failed
            try:
                await asyncio.to_thread(fn, pending.job_id, pending.value)
            except Exception:
                logger.warning("최종 상태 저장 재시도 실패: job_id=%s", pending.job_id)
                continue
            self._pending.pop(pending.job_id, None)
            logger.info("최종 상태 저장 복구: job_id=%s", pending.job_id)
