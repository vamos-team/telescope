import asyncio
import sqlite3
import threading
from datetime import date

import pytest
from fastapi.testclient import TestClient

from conftest import ANALYSIS_BODY, HEADERS, make_settings, registry_with, wait_for, wait_until
from job_shell.db import init_db
from job_shell.jobs.models import JobStatus, JobType
from job_shell.jobs.pipeline import JobContext
from job_shell.jobs.repository import JobRepository
from job_shell.jobs.service import JobService
from job_shell.jobs.worker import JobWorker, QueuedJob, WorkerUnavailableError
from job_shell.main import create_app

JOBS = "/internal/v1/jobs"


def start_analysis(client: TestClient) -> str:
    res = client.post(f"{JOBS}/analysis", json=ANALYSIS_BODY, headers=HEADERS)
    assert res.status_code == 202
    return res.json()["jobId"]


def make_worker(db_path: str) -> JobWorker:
    init_db(db_path)
    return JobWorker(
        JobRepository(db_path),
        registry_with(lambda ctx, payload: {}),
        1,
        shutdown_timeout=1,
        store_retry_attempts=1,
        store_retry_delay=0,
        pending_retry_interval=1,
    )


# --- 정상 종료 ---


def test_shutdown_waits_for_running_job_to_finish(db_path):
    release = threading.Event()

    def slow(ctx, payload):
        release.wait(5)
        return {"done": True}

    app = create_app(make_settings(db_path, shutdown_timeout_seconds=5), registry_with(slow))
    client = TestClient(app)
    client.__enter__()
    job_id = start_analysis(client)
    wait_for(client, job_id, {"RUNNING"})

    worker = app.state.worker

    def release_when_stopping():
        wait_until(lambda: not worker.is_accepting)
        release.set()

    releaser = threading.Thread(target=release_when_stopping)
    releaser.start()
    client.__exit__(None, None, None)
    releaser.join(5)

    job = JobRepository(db_path).get(job_id)
    assert job.status == JobStatus.SUCCEEDED
    assert job.result_json == '{"done": true}'


def test_shutdown_timeout_marks_running_job_failed(db_path):
    release = threading.Event()
    repo = JobRepository(db_path)

    def stuck(ctx, payload):
        release.wait(10)
        return {}

    app = create_app(make_settings(db_path, shutdown_timeout_seconds=0.1), registry_with(stuck))
    client = TestClient(app)
    client.__enter__()
    job_id = start_analysis(client)
    wait_for(client, job_id, {"RUNNING"})

    # 실행 스레드는 취소로 멈추지 않는다. FAILED가 기록된 뒤 풀어 프로세스 종료를 막지 않게 한다
    def release_when_failed():
        wait_until(lambda: repo.get(job_id).status == JobStatus.FAILED)
        release.set()

    releaser = threading.Thread(target=release_when_failed)
    releaser.start()
    client.__exit__(None, None, None)
    releaser.join(5)

    job = repo.get(job_id)
    assert job.status == JobStatus.FAILED
    assert job.error_message == "서버 종료로 작업이 중단되었습니다."


def test_submit_is_rejected_after_stop(db_path):
    worker = make_worker(db_path)
    service = JobService(JobRepository(db_path), worker)

    async def scenario():
        worker.start()
        await worker.stop()
        with pytest.raises(WorkerUnavailableError):
            worker.submit(QueuedJob(JobType.ANALYSIS, JobContext("x", date(2026, 1, 1), frozenset({"v"})), {}))
        with pytest.raises(WorkerUnavailableError):
            service.create_job(JobType.ANALYSIS, {})

    asyncio.run(scenario())


def test_submit_from_another_thread_reaches_worker(db_path):
    worker = make_worker(db_path)
    service = JobService(JobRepository(db_path), worker)
    repo = JobRepository(db_path)

    async def scenario():
        worker.start()
        # 워커가 빈 큐에서 기다리는 상태가 되도록 루프를 몇 번 돌린다(시간이 아니라 스케줄 순서로 보장)
        for _ in range(5):
            await asyncio.sleep(0)
        # 동기 핸들러처럼 다른 스레드에서 제출한다
        job_id = await asyncio.to_thread(service.create_job, JobType.ANALYSIS, {})
        for _ in range(200):
            if repo.get(job_id).status == JobStatus.SUCCEEDED:
                break
            await asyncio.sleep(0.01)
        await worker.stop()
        return job_id

    loop = asyncio.new_event_loop()
    loop.set_debug(True)  # 스레드 안전하지 않은 호출을 잡아낸다
    try:
        job_id = loop.run_until_complete(scenario())
    finally:
        loop.close()
    assert repo.get(job_id).status == JobStatus.SUCCEEDED


# --- 저장소 오류 ---


def _flaky(original, fail_times: int, counter: dict):
    def wrapper(self, *args):
        counter["calls"] += 1
        if counter["calls"] <= fail_times:
            raise sqlite3.OperationalError("database is locked")
        return original(self, *args)

    return wrapper


@pytest.mark.parametrize("fail_times", [1, 4])  # 1: 즉시 재시도로 복구, 4: 백그라운드 재시도로 복구
def test_final_save_failure_is_retried_without_rerunning_pipeline(make_client, monkeypatch, fail_times):
    pipeline_calls = {"n": 0}
    save_calls = {"calls": 0}
    monkeypatch.setattr(
        JobRepository, "mark_succeeded", _flaky(JobRepository.mark_succeeded, fail_times, save_calls)
    )

    def counted(ctx, payload):
        pipeline_calls["n"] += 1
        return {"v": 1}

    client = make_client(counted)
    job_id = start_analysis(client)
    done = wait_for(client, job_id, {"SUCCEEDED"})
    assert done["result"] == {"v": 1}
    assert pipeline_calls["n"] == 1
    assert save_calls["calls"] == fail_times + 1


def test_start_save_failure_fails_job_without_running_pipeline(make_client, monkeypatch):
    def always_fail(self, job_id):
        raise sqlite3.OperationalError("disk I/O error")

    monkeypatch.setattr(JobRepository, "mark_running", always_fail)
    ran = {"n": 0}

    def pipeline(ctx, payload):
        ran["n"] += 1
        return {}

    client = make_client(pipeline)
    job_id = start_analysis(client)
    done = wait_for(client, job_id, {"FAILED"})
    assert done["errorMessage"] == "작업을 시작하지 못했습니다."
    assert ran["n"] == 0
