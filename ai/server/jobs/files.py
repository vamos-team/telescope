"""자료 준비 작업의 원본 파일을 파이프라인이 읽을 수 있는 로컬 경로로 준비한다.

backend가 넘기는 `fileStorageKey`는 backend `FileStorage`가 발급한 키다(형식 `<UUID><확장자>`).
- 배포: 사용자 입력 파일은 S3 버킷 최상위에 있다(ai-contract 2.11). 임시 파일로 내려받고 끝나면 지운다.
- 로컬: backend와 같은 디렉터리(`./data/files`)를 본다.

파이프라인(ai 코드)은 저장소 방식을 모른 채 payload의 `file_path`(로컬 경로)만 읽는다.
"""

import logging
import shutil
import tempfile
from collections.abc import Iterator
from contextlib import AbstractContextManager, closing, contextmanager
from pathlib import Path, PurePosixPath
from typing import Any, Protocol

from jobs.pipeline import JobContext, PipelineError, PipelineFn

logger = logging.getLogger(__name__)

# 사용자에게 보이는 문구. 키·버킷·경로를 넣지 않는다(ai-contract 2.5)
MISSING_FILE_MESSAGE = "원본 파일을 찾을 수 없습니다."
READ_FAILED_MESSAGE = "원본 파일을 읽지 못했습니다."

FILE_PATH_FIELD = "file_path"
FILE_STORAGE_KEY_FIELD = "file_storage_key"


class FileSource(Protocol):
    """키로 원본을 찾아 로컬 경로를 빌려준다. 블록이 끝나면 정리한다."""

    def local_file(self, key: str) -> AbstractContextManager[Path]: ...


def _validate_key(key: str) -> str:
    """키가 상대 경로 한 칸짜리인지 확인한다. `../`나 절대 경로로 저장소 밖을 가리키지 못하게 한다."""
    parts = PurePosixPath(key).parts
    if not key or len(parts) != 1 or parts[0] in {".", ".."} or "\\" in key:
        raise PipelineError(MISSING_FILE_MESSAGE)
    return key


class LocalFileSource:
    """backend 로컬 저장소와 같은 디렉터리에서 원본을 읽는다. 복사하지 않고 그 경로를 그대로 빌려준다."""

    def __init__(self, base_dir: str) -> None:
        self._base_dir = Path(base_dir)

    @contextmanager
    def local_file(self, key: str) -> Iterator[Path]:
        """키에 해당하는 파일 경로를 돌려준다. 없으면 사용자용 문구로 실패한다."""
        path = self._base_dir / _validate_key(key)
        if not path.is_file():
            raise PipelineError(MISSING_FILE_MESSAGE)
        yield path


class S3FileSource:
    """S3 버킷에서 원본을 임시 파일로 내려받는다.

    자격증명은 boto3 기본 체인(배포는 IRSA)을 쓴다. 임시 파일은 원본 확장자를 유지한다
    (파서가 확장자로 형식을 고른다).
    """

    def __init__(self, client: Any, bucket: str) -> None:
        self._client = client
        self._bucket = bucket

    @contextmanager
    def local_file(self, key: str) -> Iterator[Path]:
        """객체를 임시 디렉터리에 받아 경로를 빌려주고, 블록이 끝나면(성공·실패 모두) 지운다."""
        _validate_key(key)
        workdir = Path(tempfile.mkdtemp(prefix="telescope-src-"))
        try:
            target = workdir / ("source" + PurePosixPath(key).suffix)
            self._download(key, target)
            yield target
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

    def _download(self, key: str, target: Path) -> None:
        """객체를 스트림으로 받아 파일에 쓴다. 없는 키와 그 밖의 오류를 사용자용 문구로 바꾼다."""
        from botocore.exceptions import BotoCoreError, ClientError

        try:
            response = self._client.get_object(Bucket=self._bucket, Key=key)
            # 파일 열기·쓰기가 실패해도 S3 응답 스트림(HTTP 연결)을 닫는다
            with closing(response["Body"]) as body, target.open("wb") as out:
                shutil.copyfileobj(body, out)
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code")
            if code in {"NoSuchKey", "404"}:
                raise PipelineError(MISSING_FILE_MESSAGE) from e
            logger.exception("S3 원본을 받지 못했다: code=%s", code)
            raise PipelineError(READ_FAILED_MESSAGE) from e
        except BotoCoreError as e:
            logger.exception("S3 원본을 받지 못했다")
            raise PipelineError(READ_FAILED_MESSAGE) from e


def with_staged_file(fn: PipelineFn, source: FileSource) -> PipelineFn:
    """파이프라인 앞에서 `file_storage_key`의 원본을 로컬로 준비해 payload의 `file_path`로 넘긴다.

    원래 payload는 바꾸지 않고 복사본에 경로를 더한다. 파이프라인이 끝나면 임시 파일을 정리한다.
    """

    def staged(ctx: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
        key = payload.get(FILE_STORAGE_KEY_FIELD)
        if not isinstance(key, str):
            raise PipelineError(MISSING_FILE_MESSAGE)
        with source.local_file(key) as path:
            return fn(ctx, {**payload, FILE_PATH_FIELD: str(path)})

    return staged
