import logging
import uuid
from collections.abc import Iterable
from datetime import date
from typing import Any

from jobs.models import Job, JobType
from jobs.pipeline import JobContext
from jobs.repository import JobRepository
from jobs.worker import SHUTDOWN_ERROR_MESSAGE, JobWorker, QueuedJob, WorkerUnavailableError

logger = logging.getLogger(__name__)

RESTART_ERROR_MESSAGE = "서버 재시작으로 작업이 중단되었습니다."


class UnsupportedJobTypeError(Exception):
    """실제 모드에서 파이프라인이 아직 등록되지 않은 작업 종류. 작업을 만들지 않고 501로 거절한다."""


class JobService:
    def __init__(self, repository: JobRepository, worker: JobWorker) -> None:
        self._repository = repository
        self._worker = worker

    def create_job(
        self,
        job_type: JobType,
        payload: dict[str, Any],
        as_of_date: date | None = None,
        allowed_document_version_ids: Iterable[str] | None = None,
    ) -> str:
        job_id = str(uuid.uuid4())
        context = JobContext(
            job_id=job_id,
            as_of_date=as_of_date,
            allowed_document_version_ids=(
                frozenset(allowed_document_version_ids)
                if allowed_document_version_ids is not None
                else None
            ),
        )
        if not self._worker.supports(job_type):
            # TODO(#86 후속): 분석·예측 파이프라인이 ai 등록 함수에 붙으면 이 거절은 생기지 않는다
            raise UnsupportedJobTypeError(job_type)
        if not self._worker.is_accepting:
            raise WorkerUnavailableError
        self._repository.create(job_id, job_type)
        try:
            self._worker.submit(QueuedJob(job_type=job_type, context=context, payload=payload))
        except WorkerUnavailableError:
            # 행을 만든 직후 종료가 시작된 경우. QUEUED로 남기지 않는다
            self._repository.mark_failed(job_id, SHUTDOWN_ERROR_MESSAGE)
            raise
        return job_id

    def get_job(self, job_id: str) -> Job | None:
        return self._repository.get(job_id)

    def fail_interrupted_jobs(self) -> None:
        """재시작 시 호출. 메모리 큐는 사라졌으므로 미종료 작업을 실패로 확정한다.

        다시 큐에 넣지 않는다: 중복 실행 위험이 있고 멱등성이 아직 정해지지 않았다.
        """
        count = self._repository.fail_unfinished(RESTART_ERROR_MESSAGE)
        if count:
            logger.warning("재시작으로 중단된 작업 %d건을 FAILED로 기록했다", count)
