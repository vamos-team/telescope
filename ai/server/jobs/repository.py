from contextlib import closing
from datetime import datetime, timezone

from db import connect
from jobs.models import Job, JobStatus, JobType


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


class JobRepository:
    """작업 상태 SQLite 저장소.

    호출마다 연결을 새로 연다. 쿼리가 작고 단일 인스턴스 전제라 연결 재사용보다
    스레드 안전성을 단순하게 유지하는 쪽을 택했다.
    상태 변경은 허용된 이전 상태일 때만 반영된다(WHERE status IN ...).
    """

    def __init__(self, db_path: str) -> None:
        self._db_path = db_path

    def create(self, job_id: str, job_type: JobType) -> None:
        with closing(connect(self._db_path)) as conn:
            conn.execute(
                "INSERT INTO jobs (job_id, job_type, status, created_at) VALUES (?, ?, ?, ?)",
                (job_id, job_type.value, JobStatus.QUEUED.value, _now()),
            )
            conn.commit()

    def get(self, job_id: str) -> Job | None:
        with closing(connect(self._db_path)) as conn:
            row = conn.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,)).fetchone()
        if row is None:
            return None
        return Job(
            job_id=row["job_id"],
            job_type=JobType(row["job_type"]),
            status=JobStatus(row["status"]),
            result_json=row["result_json"],
            error_message=row["error_message"],
            created_at=row["created_at"],
            started_at=row["started_at"],
            finished_at=row["finished_at"],
        )

    def mark_running(self, job_id: str) -> bool:
        return self._update(
            "UPDATE jobs SET status = ?, started_at = ? WHERE job_id = ? AND status = ?",
            (JobStatus.RUNNING.value, _now(), job_id, JobStatus.QUEUED.value),
        ) == 1

    def mark_succeeded(self, job_id: str, result_json: str) -> bool:
        return self._update(
            "UPDATE jobs SET status = ?, result_json = ?, finished_at = ? "
            "WHERE job_id = ? AND status IN (?, ?)",
            (JobStatus.SUCCEEDED.value, result_json, _now(), job_id,
             JobStatus.QUEUED.value, JobStatus.RUNNING.value),
        ) == 1

    def mark_failed(self, job_id: str, error_message: str) -> bool:
        return self._update(
            "UPDATE jobs SET status = ?, error_message = ?, finished_at = ? "
            "WHERE job_id = ? AND status IN (?, ?)",
            (JobStatus.FAILED.value, error_message, _now(), job_id,
             JobStatus.QUEUED.value, JobStatus.RUNNING.value),
        ) == 1

    def fail_unfinished(self, error_message: str) -> int:
        """종료되지 않은 작업을 모두 FAILED로 바꾼다. 바뀐 건수를 반환한다."""
        return self._update(
            "UPDATE jobs SET status = ?, error_message = ?, finished_at = ? "
            "WHERE status IN (?, ?)",
            (JobStatus.FAILED.value, error_message, _now(),
             JobStatus.QUEUED.value, JobStatus.RUNNING.value),
        )

    def _update(self, sql: str, params: tuple) -> int:
        with closing(connect(self._db_path)) as conn:
            cursor = conn.execute(sql, params)
            conn.commit()
            return cursor.rowcount
