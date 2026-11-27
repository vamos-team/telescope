"""PIPELINE_MODE에 따른 등록과, 실제 모드에서 미구현 작업 종류의 시작 거절."""

from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import sample_entrypoint
from conftest import ANALYSIS_BODY, HEADERS, make_settings, wait_for
from jobs.models import JobType
from jobs.registry import build_registry, load_entrypoint
from main import create_app

JOBS = "/internal/v1/jobs"
ENTRY = "sample_entrypoint:register"


def doc_body(key: str) -> dict:
    return {"documentId": "d1", "documentVersionId": "v1", "fileStorageKey": key}


@pytest.fixture
def files_dir(tmp_path) -> Path:
    path = tmp_path / "files"
    path.mkdir()
    return path


@pytest.fixture
def real_client(db_path, files_dir):
    settings = make_settings(db_path, pipeline_mode="real", pipeline_entrypoint=ENTRY, file_storage_dir=str(files_dir))
    sample_entrypoint.CALLS.clear()
    with TestClient(create_app(settings)) as client:
        yield client


def test_fake_mode_is_default_and_registers_every_job_type(db_path):
    registry = build_registry(make_settings(db_path))
    assert all(registry.supports(t) for t in JobType)


def test_real_mode_registers_only_what_the_entrypoint_registers(db_path, files_dir):
    settings = make_settings(db_path, pipeline_mode="real", pipeline_entrypoint=ENTRY, file_storage_dir=str(files_dir))
    registry = build_registry(settings)
    assert registry.supports(JobType.DOCUMENT_PREPARATION)
    assert not registry.supports(JobType.ANALYSIS)
    assert not registry.supports(JobType.PREDICTION)


def test_real_mode_passes_a_local_file_path_to_the_pipeline(real_client, files_dir):
    (files_dir / "abc.pdf").write_text("원본", encoding="utf-8")

    res = real_client.post(f"{JOBS}/document-preparation", json=doc_body("abc.pdf"), headers=HEADERS)
    body = wait_for(real_client, res.json()["jobId"], {"SUCCEEDED", "FAILED"})

    assert body["status"] == "SUCCEEDED"
    assert body["result"] == {"content": "원본"}
    call = sample_entrypoint.CALLS[0]
    assert call["file_storage_key"] == "abc.pdf" and call["existed"] and call["suffix"] == ".pdf"


def test_real_mode_fails_the_job_when_the_source_file_is_missing(real_client):
    res = real_client.post(f"{JOBS}/document-preparation", json=doc_body("missing.pdf"), headers=HEADERS)
    body = wait_for(real_client, res.json()["jobId"], {"SUCCEEDED", "FAILED"})

    assert body["status"] == "FAILED"
    assert body["errorMessage"] == "원본 파일을 찾을 수 없습니다."


def test_real_mode_rejects_unregistered_job_types_without_creating_a_job(real_client):
    res = real_client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS)

    assert res.status_code == 501
    assert res.json() == {"detail": "아직 지원하지 않는 작업입니다."}


def test_bucket_without_region_stops_at_startup(db_path):
    with pytest.raises(ValidationError, match="AWS_REGION"):
        make_settings(db_path, s3_bucket="bucket")


def test_document_preparation_without_file_source_stops_at_startup(db_path):
    settings = make_settings(db_path, pipeline_mode="real", pipeline_entrypoint=ENTRY)
    with pytest.raises(ValueError, match="FILE_STORAGE_DIR"):
        build_registry(settings)


def test_real_mode_without_entrypoint_rejects_everything_except_data_sync(db_path, tmp_path):
    nothing = build_registry(make_settings(db_path, pipeline_mode="real"))
    assert not any(nothing.supports(t) for t in JobType)

    with_sync = build_registry(make_settings(db_path, pipeline_mode="real", datasync_data_dir=str(tmp_path)))
    assert with_sync.supports(JobType.DATA_SYNC)
    assert not with_sync.supports(JobType.DOCUMENT_PREPARATION)


def test_fake_mode_never_fakes_a_data_sync(db_path):
    from jobs.pipeline import JobContext, PipelineError

    registry = build_registry(make_settings(db_path))
    ctx = JobContext(job_id="j", as_of_date=None, allowed_document_version_ids=None)
    with pytest.raises(PipelineError, match="적재하지 않습니다"):
        registry.get(JobType.DATA_SYNC)(ctx, {})


@pytest.mark.parametrize("spec", ["no_colon", ":register", "sample_entrypoint:", "sample_entrypoint:missing"])
def test_bad_entrypoint_spec_is_rejected(spec):
    with pytest.raises(ValueError, match="PIPELINE_ENTRYPOINT"):
        load_entrypoint(spec)


def test_pipeline_keys_in_the_shared_dotenv_do_not_stop_startup(tmp_path):
    from config import Settings

    env = tmp_path / ".env"
    env.write_text(
        "INTERNAL_API_KEY=x\nDB_PATH=/tmp/j.db\nMAX_CONCURRENT_JOBS=3\n"
        "OPENAI_API_KEY=dummy\nPARSER_EMBED_MODEL=some/model\nHF_TOKEN=dummy\n",
        encoding="utf-8",
    )
    settings = Settings(_env_file=str(env))
    assert settings.max_concurrent_jobs == 3  # 서버 키는 그대로 읽고 검증한다


def test_declared_server_keys_are_still_validated_from_dotenv(tmp_path):
    from config import Settings

    env = tmp_path / ".env"
    env.write_text("INTERNAL_API_KEY=x\nDB_PATH=/tmp/j.db\nMAX_CONCURRENT_JOBS=0\n", encoding="utf-8")
    with pytest.raises(ValidationError, match="max_concurrent_jobs"):
        Settings(_env_file=str(env))
