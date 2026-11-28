import logging
import uuid
from collections.abc import Iterable
from datetime import date
from typing import Any

from job_shell.jobs.models import Job, JobType
from job_shell.jobs.pipeline import JobContext
from job_shell.jobs.repository import JobRepository
from job_shell.jobs.worker import SHUTDOWN_ERROR_MESSAGE, JobWorker, QueuedJob, WorkerUnavailableError

logger = logging.getLogger(__name__)

RESTART_ERROR_MESSAGE = "서버 재시작으로 작업이 중단되었습니다."


# 멱등 키에서 job_id를 정하는 이름공간. 바꾸면 이미 받은 요청을 다시 받았을 때 새 작업이 생긴다
_IDEMPOTENCY_NAMESPACE = uuid.UUID("5d2c6f0e-1b3a-4c8e-9f57-135000000135")


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
        idempotency_key: str | None = None,
    ) -> str:
        """작업을 만들고 큐에 넣은 뒤 job_id를 돌려준다.

        idempotency_key가 있으면 job_id를 그 키에서 정해서 만든다. 같은 키로 이미 만든 작업이 있으면 새로 만들지 않고
        그 job_id를 돌려준다(요청 내용은 쓰지 않는다). backend가 시작 응답을 못 받았을 때 다시 보내도 두 번 돌지 않게
        하려는 것이다(데이터 적재, #135).
        """
        job_id = (
            str(uuid.uuid5(_IDEMPOTENCY_NAMESPACE, f"{job_type.value}:{idempotency_key}"))
            if idempotency_key is not None
            else str(uuid.uuid4())
        )
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
        if idempotency_key is None:
            self._repository.create(job_id, job_type)
        elif not self._repository.create_if_absent(job_id, job_type):
            return job_id
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
