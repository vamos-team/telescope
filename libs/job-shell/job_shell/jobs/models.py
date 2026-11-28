from dataclasses import dataclass
from enum import StrEnum


class JobStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class JobType(StrEnum):
    DOCUMENT_PREPARATION = "document-preparation"
    ANALYSIS = "analysis"
    PREDICTION = "prediction"
    DATA_SYNC = "data-sync"


@dataclass(frozen=True)
class Job:
    job_id: str
    job_type: JobType
    status: JobStatus
    result_json: str | None
    error_message: str | None
    created_at: str
    started_at: str | None
    finished_at: str | None
