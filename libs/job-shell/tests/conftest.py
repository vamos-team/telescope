import time
from collections.abc import Callable, Iterator

import pytest
from fastapi.testclient import TestClient

from job_shell.config import Settings
from job_shell.jobs.models import JobType
from job_shell.jobs.pipeline import PipelineFn
from job_shell.jobs.registry import PipelineRegistry
from job_shell.main import create_app

API_KEY = "test-key"
HEADERS = {"X-Internal-Api-Key": API_KEY}

ANALYSIS_BODY = {
    "analysisRunId": "run-1",
    "allowedDocumentVersionIds": ["v1", "v2"],
    "asOfDate": "2026-06-30",
}


def make_settings(db_path: str, max_concurrent_jobs: int = 2, **overrides) -> Settings:
    values = {
        "internal_api_key": API_KEY,
        "db_path": db_path,
        "max_concurrent_jobs": max_concurrent_jobs,
        "shutdown_timeout_seconds": 5.0,
        "store_retry_attempts": 2,
        "store_retry_delay_seconds": 0.01,
        "pending_retry_interval_seconds": 0.05,
        **overrides,
    }
    return Settings(_env_file=None, **values)


def wait_until(condition: Callable[[], bool], timeout: float = 5.0) -> None:
    deadline = time.monotonic() + timeout
    while not condition():
        if time.monotonic() > deadline:
            raise AssertionError("timeout waiting for condition")
        time.sleep(0.01)


def registry_with(fn: PipelineFn) -> PipelineRegistry:
    registry = PipelineRegistry()
    for job_type in JobType:
        registry.register(job_type, fn)
    return registry


@pytest.fixture
def db_path(tmp_path) -> str:
    return str(tmp_path / "jobs.db")


@pytest.fixture
def make_client(db_path) -> Iterator[Callable[..., TestClient]]:
    clients: list[TestClient] = []

    def _make(fn: PipelineFn, max_concurrent_jobs: int = 2) -> TestClient:
        app = create_app(make_settings(db_path, max_concurrent_jobs), registry_with(fn))
        client = TestClient(app)
        client.__enter__()
        clients.append(client)
        return client

    yield _make
    for client in clients:
        client.__exit__(None, None, None)


def wait_for(client: TestClient, job_id: str, statuses: set[str], timeout: float = 5.0) -> dict:
    deadline = time.monotonic() + timeout
    while True:
        body = client.get(f"/internal/v1/jobs/{job_id}", headers=HEADERS).json()
        if body["status"] in statuses:
            return body
        if time.monotonic() > deadline:
            raise AssertionError(f"timeout: last={body}")
        time.sleep(0.02)
