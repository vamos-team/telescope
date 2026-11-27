"""실제 모드 테스트용 ai 등록 함수. 자료 준비만 구현한 상태(분석·예측 미구현)를 흉내 낸다."""

from pathlib import Path

from jobs.models import JobType

# 파이프라인이 받은 payload를 테스트가 확인할 수 있게 남긴다
CALLS: list[dict] = []


def prepare(ctx, payload):
    """받은 file_path의 내용을 읽어 돌려준다. 실행 중에 파일이 실제로 있는지 확인하는 용도."""
    path = Path(payload["file_path"])
    CALLS.append({**payload, "existed": path.exists(), "suffix": path.suffix})
    return {"content": path.read_text(encoding="utf-8")}


def register(registry):
    """자료 준비만 붙인다."""
    registry.register(JobType.DOCUMENT_PREPARATION, prepare)


NOT_CALLABLE = "등록 함수가 아님"
