import json
from datetime import date
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from pydantic.alias_generators import to_camel

from auth import verify_internal_api_key
from jobs.models import JobStatus, JobType
from jobs.service import JobService

NonEmptyStr = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


# 요청 바디: 확정 전. backend PythonAiClient DTO 초안(ai-contract 2.4)에 맞춘 임시 필드다.
# 작업별 입출력 이슈에서 확정되면 바꾼다.
class DocumentPreparationJobRequest(CamelModel):
    document_id: NonEmptyStr
    document_version_id: NonEmptyStr
    file_storage_key: NonEmptyStr


class AnalysisJobRequest(CamelModel):
    analysis_run_id: NonEmptyStr
    allowed_document_version_ids: list[NonEmptyStr] = Field(min_length=1)
    as_of_date: date
    profile_snapshot_json: str | None = None


class PredictionJobRequest(CamelModel):
    analysis_run_id: NonEmptyStr
    as_of_date: date


class DataSyncJobRequest(CamelModel):
    """재무 데이터 적재 요청(#135). 대상 회사와 기간은 backend가 설정에서 정해 보낸다."""

    data_sync_run_id: NonEmptyStr
    corp_codes: list[NonEmptyStr] = Field(min_length=1)
    start: date
    end: date


class JobStartResponse(CamelModel):
    job_id: str


class JobStatusResponse(CamelModel):
    job_id: str
    status: JobStatus
    result: Any | None
    error_message: str | None


# 기준 시점·허용 문서는 payload에서 빼고 JobContext로만 넘긴다(파이프라인이 바꾸지 못하게)
CONTEXT_FIELDS = {"as_of_date", "allowed_document_version_ids"}

router = APIRouter(prefix="/internal/v1/jobs", dependencies=[Depends(verify_internal_api_key)])


def get_job_service(request: Request) -> JobService:
    return request.app.state.job_service


ServiceDep = Annotated[JobService, Depends(get_job_service)]


def _payload(body: BaseModel) -> dict[str, Any]:
    return body.model_dump(mode="json", exclude=CONTEXT_FIELDS)


@router.post("/document-preparation", status_code=status.HTTP_202_ACCEPTED, response_model=JobStartResponse)
def start_document_preparation(body: DocumentPreparationJobRequest, service: ServiceDep) -> JobStartResponse:
    job_id = service.create_job(JobType.DOCUMENT_PREPARATION, _payload(body))
    return JobStartResponse(job_id=job_id)


@router.post("/analysis", status_code=status.HTTP_202_ACCEPTED, response_model=JobStartResponse)
def start_analysis(body: AnalysisJobRequest, service: ServiceDep) -> JobStartResponse:
    job_id = service.create_job(
        JobType.ANALYSIS,
        _payload(body),
        as_of_date=body.as_of_date,
        allowed_document_version_ids=body.allowed_document_version_ids,
    )
    return JobStartResponse(job_id=job_id)


@router.post("/prediction", status_code=status.HTTP_202_ACCEPTED, response_model=JobStartResponse)
def start_prediction(body: PredictionJobRequest, service: ServiceDep) -> JobStartResponse:
    # 허용 문서 목록이 아직 계약에 없다(ai-contract 2.9 간극). 추가되면 여기서 넘긴다
    job_id = service.create_job(JobType.PREDICTION, _payload(body), as_of_date=body.as_of_date)
    return JobStartResponse(job_id=job_id)


@router.post("/data-sync", status_code=status.HTTP_202_ACCEPTED, response_model=JobStartResponse)
def start_data_sync(body: DataSyncJobRequest, service: ServiceDep) -> JobStartResponse:
    """재무 데이터 적재를 시작한다. 시작일이 종료일보다 늦으면 422.

    같은 dataSyncRunId로 다시 오면 새로 만들지 않고 처음 만든 작업의 jobId를 돌려준다(멱등). backend가 시작 응답을
    받지 못했거나 받은 번호를 저장하지 못했을 때 다시 보내기 때문이다.
    """
    if body.start > body.end:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail="start는 end보다 늦을 수 없습니다.")
    job_id = service.create_job(JobType.DATA_SYNC, _payload(body), idempotency_key=body.data_sync_run_id)
    return JobStartResponse(job_id=job_id)


@router.get("/{job_id}", response_model=JobStatusResponse)
def get_job(job_id: str, service: ServiceDep) -> JobStatusResponse:
    job = service.get_job(job_id)
    if job is None:
        # 404는 "모르는 jobId"일 때만 쓴다(ai-contract 2.5)
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="작업을 찾을 수 없습니다.")
    result = json.loads(job.result_json) if job.status == JobStatus.SUCCEEDED and job.result_json else None
    return JobStatusResponse(
        job_id=job.job_id,
        status=job.status,
        result=result,
        error_message=job.error_message if job.status == JobStatus.FAILED else None,
    )
