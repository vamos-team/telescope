"""원본 파일 준비: 로컬 폴더와 S3(botocore Stubber로 실제 S3 없이)."""

import io
from pathlib import Path

import boto3
import pytest
from botocore.response import StreamingBody
from botocore.stub import Stubber

from job_shell.jobs.files import LocalFileSource, S3FileSource, with_staged_file
from job_shell.jobs.pipeline import JobContext, PipelineError

BUCKET = "test-bucket"
CTX = JobContext(job_id="j1", as_of_date=None, allowed_document_version_ids=None)


@pytest.fixture
def s3():
    client = boto3.client("s3", region_name="ap-northeast-2", aws_access_key_id="x", aws_secret_access_key="x")
    with Stubber(client) as stubber:
        yield client, stubber


def body(content: bytes) -> StreamingBody:
    return StreamingBody(io.BytesIO(content), len(content))


def test_local_source_lends_the_existing_path(tmp_path):
    (tmp_path / "a.pdf").write_bytes(b"x")
    with LocalFileSource(str(tmp_path)).local_file("a.pdf") as path:
        assert path == tmp_path / "a.pdf"


@pytest.mark.parametrize("key", ["missing.pdf", "../outside.pdf", "/etc/passwd", "sub/a.pdf", ".."])
def test_local_source_rejects_missing_or_escaping_keys(tmp_path, key):
    with pytest.raises(PipelineError, match="원본 파일을 찾을 수 없습니다"):
        with LocalFileSource(str(tmp_path)).local_file(key):
            pass


def test_s3_source_downloads_to_a_temp_file_with_the_same_suffix_and_removes_it(s3):
    client, stubber = s3
    stubber.add_response("get_object", {"Body": body(b"hello")}, {"Bucket": BUCKET, "Key": "abc.pdf"})

    with S3FileSource(client, BUCKET).local_file("abc.pdf") as path:
        assert path.suffix == ".pdf"
        assert path.read_bytes() == b"hello"
        kept = path
    assert not kept.exists()
    assert not kept.parent.exists()


def test_s3_source_turns_missing_keys_into_a_user_message(s3):
    client, stubber = s3
    stubber.add_client_error("get_object", service_error_code="NoSuchKey", http_status_code=404)

    with pytest.raises(PipelineError, match="원본 파일을 찾을 수 없습니다"):
        with S3FileSource(client, BUCKET).local_file("missing.pdf"):
            pass


def test_s3_source_hides_other_errors_behind_a_generic_message(s3):
    client, stubber = s3
    stubber.add_client_error("get_object", service_error_code="AccessDenied", http_status_code=403)

    with pytest.raises(PipelineError) as e:
        with S3FileSource(client, BUCKET).local_file("abc.pdf"):
            pass
    assert e.value.user_message == "원본 파일을 읽지 못했습니다."
    assert BUCKET not in e.value.user_message


def test_staged_file_cleans_up_even_when_the_pipeline_fails(s3):
    client, stubber = s3
    stubber.add_response("get_object", {"Body": body(b"hello")}, {"Bucket": BUCKET, "Key": "abc.pdf"})
    seen: list[Path] = []

    def failing(ctx, payload):
        seen.append(Path(payload["file_path"]))
        raise PipelineError("파싱 실패")

    staged = with_staged_file(failing, S3FileSource(client, BUCKET))
    payload = {"file_storage_key": "abc.pdf"}
    with pytest.raises(PipelineError, match="파싱 실패"):
        staged(CTX, payload)

    assert not seen[0].exists()
    assert "file_path" not in payload  # 원래 payload는 바꾸지 않는다


def test_s3_source_closes_the_response_stream_even_when_writing_fails(s3, monkeypatch):
    client, stubber = s3
    raw = io.BytesIO(b"hello")
    stubber.add_response("get_object", {"Body": StreamingBody(raw, 5)}, {"Bucket": BUCKET, "Key": "abc.pdf"})

    def broken_copy(src, dst):
        raise OSError("디스크 가득 참")

    monkeypatch.setattr("jobs.files.shutil.copyfileobj", broken_copy)

    with pytest.raises(OSError):
        with S3FileSource(client, BUCKET).local_file("abc.pdf"):
            pass
    assert raw.closed
