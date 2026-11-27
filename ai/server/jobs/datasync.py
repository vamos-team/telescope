"""재무 데이터 적재 작업(#135). DART에서 정기공시 XBRL을 받고, 받아 둔 원본 전체로 새 DuckDB 파일을 만든다.

배치를 돌리는 일(언제·무엇을·결과 기록)은 backend 담당이고, 수집기(`pipelines/collectors/dart_xbrl.py`)·
적재기(`pipelines/loaders/xbrl.py`) 코드는 ai 담당이다. 이 모듈은 그 둘을 순서대로 부르는 연결만 한다.

- 판마다 다른 파일을 만든다: `<data_dir>/duckdb/finance-<dataSyncRunId>.duckdb`. 기존 판 파일은 건드리지 않는다
  (적재기가 `.tmp`로 만들고 검증한 뒤 이름을 바꾼다). 지금 쓸 판은 backend가 정한다(`DataVersion`).
- 원본은 `<data_dir>/raw/`에 쌓는다. 이미 받은 파일은 수집기가 다시 받지 않는다.
- 수집기·적재기는 무거운 의존성(duckdb, lxml, 임베딩 모델)을 쓰므로 실행할 때 불러온다.
"""

import logging
from pathlib import Path
from typing import Any, Callable

from jobs.pipeline import JobContext, PipelineError, PipelineFn

logger = logging.getLogger(__name__)

# 사용자(관리자)에게 보이는 문구. 내부 경로·API 키·예외 원문을 넣지 않는다
API_KEY_MISSING_MESSAGE = "DART API 키가 설정되지 않았습니다."
COLLECT_FAILED_MESSAGE = "DART에서 공시를 받지 못했습니다."
LOAD_FAILED_MESSAGE = "재무 데이터를 적재하지 못했습니다."
NOTHING_TO_LOAD_MESSAGE = "적재할 공시가 없습니다."

Collector = Callable[[str, str, str, Path], dict]
TaxonomyCollector = Callable[[Path], dict]
Loader = Callable[[Path, list[Path], Path], dict]


def _default_collect(corp_code: str, start: str, end: str, raw_dir: Path) -> dict:
    """ai 담당 수집기를 부른다(회사 하나의 정기공시 XBRL zip)."""
    from collectors.dart_xbrl import collect

    return collect(corp_code, start, end, raw_dir)


def _default_collect_taxonomy(raw_dir: Path) -> dict:
    """ai 담당 수집기로 받은 XBRL이 참조하는 DART 택소노미를 받는다."""
    from collectors.dart_xbrl import collect_taxonomy

    return collect_taxonomy(raw_dir)


def _default_load(out: Path, zips: list[Path], taxonomy_dir: Path) -> dict:
    """ai 담당 적재기를 부른다(zip 전체 → 새 DuckDB 파일, 검증 후 교체)."""
    from loaders.xbrl import load

    return load(out, zips, taxonomy_dir)


def _is_missing_api_key(error: BaseException) -> bool:
    """수집기가 API 키가 없어 멈췄는지. 키가 없으면 수집기는 메시지에 변수명을 담아 SystemExit를 던진다."""
    return "OPENDART_API_KEY" in str(error)


def make_data_sync_pipeline(
    data_dir: str,
    collect: Collector = _default_collect,
    collect_taxonomy: TaxonomyCollector = _default_collect_taxonomy,
    load: Loader = _default_load,
) -> PipelineFn:
    """데이터 적재 파이프라인을 만든다. 수집기·적재기는 테스트에서 가짜로 바꿔 끼운다.

    payload: `data_sync_run_id`, `corp_codes`(DART 고유번호 목록), `start`·`end`(`yyyy-MM-dd`).
    결과: `{ "duckdbPath", "filings", "replaced", "collected": {고유번호: 수집기 통계} }`.
    """
    root = Path(data_dir)

    def run(ctx: JobContext, payload: dict[str, Any]) -> dict[str, Any]:
        raw_dir = root / "raw"
        out = root / "duckdb" / f"finance-{payload['data_sync_run_id']}.duckdb"
        start = str(payload["start"]).replace("-", "")
        end = str(payload["end"]).replace("-", "")

        collected: dict[str, dict] = {}
        try:
            for corp_code in payload["corp_codes"]:
                collected[corp_code] = collect(corp_code, start, end, raw_dir)
            collect_taxonomy(raw_dir)
        # 수집기는 네트워크·API 오류를 여러 종류로 던지고, API 키가 없으면 SystemExit를 던진다(명령줄 도구로 만들어져서).
        # SystemExit는 Exception이 아니라 그냥 두면 작업 실행기 스레드까지 올라가므로 여기서 함께 잡는다.
        except (Exception, SystemExit) as e:
            if _is_missing_api_key(e):
                raise PipelineError(API_KEY_MISSING_MESSAGE) from e
            logger.exception("DART 수집 실패: job_id=%s", ctx.job_id)
            raise PipelineError(COLLECT_FAILED_MESSAGE) from e

        zips = sorted((raw_dir / "dart_xbrl").glob("*/*.zip"))
        if not zips:
            raise PipelineError(NOTHING_TO_LOAD_MESSAGE)
        out.parent.mkdir(parents=True, exist_ok=True)
        try:
            stats = load(out, zips, raw_dir / "dart_taxonomy")
        except (Exception, SystemExit) as e:
            logger.exception("DuckDB 적재 실패: job_id=%s", ctx.job_id)
            raise PipelineError(LOAD_FAILED_MESSAGE) from e

        return {
            "duckdbPath": str(out),
            "filings": stats.get("filings"),
            "replaced": stats.get("replaced"),
            "collected": collected,
        }

    return run
