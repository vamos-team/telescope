"""재무 데이터 적재 작업: 수집기·적재기를 가짜로 바꿔 순서·결과·실패 문구를 본다(실제 DART 호출 없음)."""

from pathlib import Path

import pytest

from conftest import HEADERS, wait_for
from jobs.datasync import (
    API_KEY_MISSING_MESSAGE,
    COLLECT_FAILED_MESSAGE,
    LOAD_FAILED_MESSAGE,
    NOTHING_TO_LOAD_MESSAGE,
    make_data_sync_pipeline,
)
from jobs.pipeline import JobContext, PipelineError

CTX = JobContext(job_id="j1", as_of_date=None, allowed_document_version_ids=None)
PAYLOAD = {"data_sync_run_id": "7", "corp_codes": ["00159023", "00190321"], "start": "2021-01-01", "end": "2026-11-27"}


class Fakes:
    """수집기·적재기 대역. 부른 순서와 인자를 남긴다."""

    def __init__(self, tmp_path: Path):
        self.calls: list[tuple] = []
        self.tmp_path = tmp_path

    def collect(self, corp_code, start, end, raw_dir):
        self.calls.append(("collect", corp_code, start, end))
        zip_dir = raw_dir / "dart_xbrl" / corp_code
        zip_dir.mkdir(parents=True, exist_ok=True)
        (zip_dir / "20260515000123_11013.zip").write_bytes(b"zip")
        return {"saved": 1, "exists": 0}

    def collect_taxonomy(self, raw_dir):
        self.calls.append(("taxonomy",))
        return {"saved": 0}

    def load(self, out, zips, taxonomy_dir):
        self.calls.append(("load", out.name, len(zips), taxonomy_dir.name))
        out.write_bytes(b"duckdb")
        return {"filings": len(zips), "replaced": 0}


def test_collects_every_company_then_loads_into_a_new_file_per_run(tmp_path):
    f = Fakes(tmp_path)
    run = make_data_sync_pipeline(str(tmp_path), f.collect, f.collect_taxonomy, f.load)

    result = run(CTX, PAYLOAD)

    assert f.calls == [
        ("collect", "00159023", "20210101", "20261127"),
        ("collect", "00190321", "20210101", "20261127"),
        ("taxonomy",),
        ("load", "finance-7.duckdb", 2, "dart_taxonomy"),
    ]
    assert result["duckdbPath"] == str(tmp_path / "duckdb" / "finance-7.duckdb")
    assert result["filings"] == 2
    assert set(result["collected"]) == {"00159023", "00190321"}
    assert Path(result["duckdbPath"]).exists()


def test_missing_api_key_is_reported_without_internal_details(tmp_path):
    def no_key(*args):
        raise SystemExit("OPENDART_API_KEY가 없습니다 (환경변수 또는 .env)")  # 수집기가 실제로 던지는 모양

    run = make_data_sync_pipeline(str(tmp_path), no_key, lambda raw: {}, lambda *a: {})
    with pytest.raises(PipelineError) as e:
        run(CTX, PAYLOAD)
    assert e.value.user_message == API_KEY_MISSING_MESSAGE


@pytest.mark.parametrize("stage, message", [("collect", COLLECT_FAILED_MESSAGE), ("load", LOAD_FAILED_MESSAGE)])
def test_collect_and_load_failures_use_generic_messages(tmp_path, stage, message):
    f = Fakes(tmp_path)

    def boom(*args):
        raise RuntimeError("/secret/path 내부 오류")

    run = make_data_sync_pipeline(str(tmp_path), boom if stage == "collect" else f.collect, f.collect_taxonomy,
                                  boom if stage == "load" else f.load)
    with pytest.raises(PipelineError) as e:
        run(CTX, PAYLOAD)
    assert e.value.user_message == message


def test_nothing_collected_does_not_overwrite_anything(tmp_path):
    f = Fakes(tmp_path)
    run = make_data_sync_pipeline(str(tmp_path), lambda *a: {"saved": 0}, f.collect_taxonomy, f.load)

    with pytest.raises(PipelineError, match=NOTHING_TO_LOAD_MESSAGE):
        run(CTX, PAYLOAD)
    assert not any(c[0] == "load" for c in f.calls)


def test_data_sync_route_validates_and_starts_a_job(make_client):
    seen = []
    client = make_client(lambda ctx, payload: seen.append(payload) or {"duckdbPath": "/x"})

    bad = client.post("/internal/v1/jobs/data-sync", headers=HEADERS,
                      json={"dataSyncRunId": "7", "corpCodes": ["0"], "start": "2026-02-01", "end": "2026-01-01"})
    assert bad.status_code == 422
    empty = client.post("/internal/v1/jobs/data-sync", headers=HEADERS,
                        json={"dataSyncRunId": "7", "corpCodes": [], "start": "2026-01-01", "end": "2026-02-01"})
    assert empty.status_code == 422

    res = client.post("/internal/v1/jobs/data-sync", headers=HEADERS,
                      json={"dataSyncRunId": "7", "corpCodes": ["00159023"], "start": "2021-01-01", "end": "2026-11-27"})
    assert res.status_code == 202
    body = wait_for(client, res.json()["jobId"], {"SUCCEEDED", "FAILED"})
    assert body["status"] == "SUCCEEDED"
    assert seen[0] == {"data_sync_run_id": "7", "corp_codes": ["00159023"], "start": "2021-01-01", "end": "2026-11-27"}
