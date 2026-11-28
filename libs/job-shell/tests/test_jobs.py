import dataclasses
import threading
import time
from datetime import date

import pytest

from conftest import ANALYSIS_BODY, HEADERS, wait_for, wait_until
from job_shell.jobs.models import JobType
from job_shell.jobs.pipeline import PipelineError
from job_shell.jobs.repository import JobRepository
from job_shell.db import init_db

JOBS = "/internal/v1/jobs"


def ok(ctx, payload):
    return {"ok": True}


def test_health_does_not_require_auth(make_client):
    client = make_client(ok)
    assert client.get("/health").json() == {"status": "ok"}


@pytest.mark.parametrize("headers", [{}, {"X-Internal-Api-Key": "wrong"}])
def test_rejects_missing_or_wrong_api_key(make_client, headers):
    client = make_client(ok)
    assert client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=headers).status_code == 401
    assert client.get(f"{JOBS}/anything", headers=headers).status_code == 401


def test_start_returns_job_id_immediately_and_transitions_to_succeeded(make_client):
    release = threading.Event()

    def slow(ctx, payload):
        release.wait(5)
        return {"answer": 42}

    client = make_client(slow)
    started = time.monotonic()
    res = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS)
    assert time.monotonic() - started < 1
    assert res.status_code == 202
    job_id = res.json()["jobId"]

    running = wait_for(client, job_id, {"RUNNING"})
    assert running["result"] is None and running["errorMessage"] is None

    release.set()
    done = wait_for(client, job_id, {"SUCCEEDED"})
    assert done == {"jobId": job_id, "status": "SUCCEEDED", "result": {"answer": 42}, "errorMessage": None}
    # 종료 후에도 같은 결과를 반복 조회할 수 있다
    assert client.get(f"{JOBS}/{job_id}", headers=HEADERS).json() == done


def test_unknown_job_is_404(make_client):
    client = make_client(ok)
    res = client.get(f"{JOBS}/does-not-exist", headers=HEADERS)
    assert res.status_code == 404


def test_pipeline_error_message_is_exposed(make_client):
    def fail(ctx, payload):
        raise PipelineError("허용 문서가 없습니다")

    client = make_client(fail)
    job_id = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS).json()["jobId"]
    done = wait_for(client, job_id, {"FAILED"})
    assert done["errorMessage"] == "허용 문서가 없습니다"
    assert done["result"] is None


def test_unexpected_exception_hides_internal_details(make_client):
    def crash(ctx, payload):
        raise RuntimeError("/secret/path/file.pdf 원문 내용")

    client = make_client(crash)
    job_id = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS).json()["jobId"]
    done = wait_for(client, job_id, {"FAILED"})
    assert done["errorMessage"] == "작업 처리 중 오류가 발생했습니다."


def test_non_serializable_result_is_failure(make_client):
    client = make_client(lambda ctx, payload: {"bad": object()})
    job_id = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS).json()["jobId"]
    assert wait_for(client, job_id, {"FAILED"})["errorMessage"] == "작업 처리 중 오류가 발생했습니다."


def test_long_error_message_is_truncated(make_client):
    def fail(ctx, payload):
        raise PipelineError("가" * 600)

    client = make_client(fail)
    job_id = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS).json()["jobId"]
    assert len(wait_for(client, job_id, {"FAILED"})["errorMessage"]) == 500


def test_concurrency_is_limited(make_client):
    lock = threading.Lock()
    state = {"current": 0, "max": 0}
    release = threading.Event()

    def tracked(ctx, payload):
        with lock:
            state["current"] += 1
            state["max"] = max(state["max"], state["current"])
        release.wait(5)
        with lock:
            state["current"] -= 1
        return {}

    client = make_client(tracked, max_concurrent_jobs=2)
    job_ids = [
        client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS).json()["jobId"] for _ in range(3)
    ]
    # 워커 2개가 모두 막혀 있으면 세 번째는 시작될 수 없다
    wait_until(lambda: state["current"] == 2)
    statuses = sorted(client.get(f"{JOBS}/{j}", headers=HEADERS).json()["status"] for j in job_ids)
    assert statuses == ["QUEUED", "RUNNING", "RUNNING"]

    release.set()
    for job_id in job_ids:
        wait_for(client, job_id, {"SUCCEEDED"})
    assert state["max"] == 2


def test_context_is_fixed_and_excluded_from_payload(make_client):
    seen = {}

    def capture(ctx, payload):
        seen["ctx"], seen["payload"] = ctx, payload
        try:
            ctx.as_of_date = date(2099, 1, 1)
        except dataclasses.FrozenInstanceError:
            seen["frozen"] = True
        return {}

    client = make_client(capture)
    job_id = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS).json()["jobId"]
    wait_for(client, job_id, {"SUCCEEDED"})

    ctx = seen["ctx"]
    assert ctx.job_id == job_id
    assert ctx.as_of_date == date(2026, 6, 30)
    assert ctx.allowed_document_version_ids == frozenset({"v1", "v2"})
    assert seen["frozen"] is True
    assert "asOfDate" not in seen["payload"] and "as_of_date" not in seen["payload"]
    assert "allowed_document_version_ids" not in seen["payload"]
    assert seen["payload"]["analysis_run_id"] == "run-1"


@pytest.mark.parametrize(
    "body",
    [
        {**ANALYSIS_BODY, "allowedDocumentVersionIds": []},
        {**ANALYSIS_BODY, "allowedDocumentVersionIds": [" "]},
        {k: v for k, v in ANALYSIS_BODY.items() if k != "asOfDate"},
    ],
)
def test_rejects_missing_scope(make_client, body):
    client = make_client(ok)
    assert client.post(f"{JOBS}/analysis", json=body, headers=HEADERS).status_code == 422


def test_validation_error_does_not_echo_input(make_client):
    client = make_client(ok)
    secret = "문서 본문 일부"
    res = client.post(f"{JOBS}/analysis", json={**ANALYSIS_BODY, "asOfDate": secret}, headers=HEADERS)
    assert res.status_code == 422
    assert secret not in res.text


@pytest.mark.parametrize(
    "path, body",
    [
        ("document-preparation", {"documentId": "d1", "documentVersionId": "v1", "fileStorageKey": "k"}),
        ("prediction", {"analysisRunId": "run-1", "asOfDate": "2026-06-30"}),
    ],
)
def test_other_job_types_start(make_client, path, body):
    client = make_client(ok)
    res = client.post(f"{JOBS}/{path}", json=body, headers=HEADERS)
    assert res.status_code == 202
    wait_for(client, res.json()["jobId"], {"SUCCEEDED"})


def test_restart_fails_unfinished_jobs_and_keeps_finished(db_path, make_client):
    init_db(db_path)
    repo = JobRepository(db_path)
    repo.create("queued", JobType.ANALYSIS)
    repo.create("running", JobType.ANALYSIS)
    repo.mark_running("running")
    repo.create("done", JobType.ANALYSIS)
    repo.mark_succeeded("done", '{"x": 1}')

    client = make_client(ok)  # 서버 기동 = 재시작

    for job_id in ("queued", "running"):
        body = client.get(f"{JOBS}/{job_id}", headers=HEADERS).json()
        assert body["status"] == "FAILED"
        assert body["errorMessage"] == "서버 재시작으로 작업이 중단되었습니다."
    done = client.get(f"{JOBS}/done", headers=HEADERS).json()
    assert done["status"] == "SUCCEEDED" and done["result"] == {"x": 1}
