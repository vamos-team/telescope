"""네이버 증권 종목별 리서치 목록 수집.

렌더링된 목록 페이지 원본(HTML)과 상세 링크 목록을 data/raw 아래에 저장한다.
파싱·가공은 하지 않는다 (pipelines/parsers 담당).

저장 위치: data/raw/naver_research/<종목코드>/<수집시각 UTC>/
    page.html   렌더링된 목록 페이지 원본
    links.json  출처 URL, 수집 시각, 상세 링크(url, text) 목록. 페이지 순서 그대로이며 중복도 남긴다

실행: python -m pipelines.collectors.naver_research [종목코드 ...]
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

SOURCE = "naver_research"
BASE_URL = "https://stock.naver.com"
RAW_DIR = Path(__file__).resolve().parents[2] / "data" / "raw"

# 통신 3사: SK텔레콤, KT, LG유플러스
DEFAULT_STOCK_CODES = ("017670", "030200", "032640")

WAIT_SECONDS = 15


def _build_driver() -> webdriver.Chrome:
    options = Options()
    options.add_argument("--headless=new")
    options.add_argument("--window-size=1440,1200")
    options.add_argument("--lang=ko-KR")
    return webdriver.Chrome(options=options)


def collect(
    stock_code: str,
    driver: webdriver.Chrome,
    raw_dir: Path = RAW_DIR,
) -> Path:
    """종목 하나의 리서치 목록을 수집해 저장하고, 저장한 폴더를 돌려준다."""
    path = f"/domestic/stock/{stock_code}/research"
    url = f"{BASE_URL}{path}"
    collected_at = datetime.now(timezone.utc)

    driver.get(url)

    # 종목별 리서치 상세 링크가 실제 DOM에 렌더링될 때까지 대기
    anchors = WebDriverWait(driver, WAIT_SECONDS).until(
        EC.presence_of_all_elements_located(
            (By.CSS_SELECTOR, f'a[href^="{path}/"]')
        )
    )

    # 페이지에 나온 순서 그대로 전부 남긴다. 중복 제거와 텍스트 정리는 parsers.research_links 담당
    links = [
        {"url": anchor.get_attribute("href"), "text": anchor.text} for anchor in anchors
    ]

    # 이전 수집분을 덮어쓰지 않도록 수집 시각별 폴더에 새로 쌓는다
    out_dir = raw_dir / SOURCE / stock_code / collected_at.strftime("%Y%m%dT%H%M%SZ")
    out_dir.mkdir(parents=True, exist_ok=False)

    (out_dir / "page.html").write_text(driver.page_source, encoding="utf-8")
    (out_dir / "links.json").write_text(
        json.dumps(
            {
                "source": SOURCE,
                "source_url": url,
                "stock_code": stock_code,
                "collected_at": collected_at.isoformat(),
                "links": links,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return out_dir


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "stock_codes",
        nargs="*",
        default=list(DEFAULT_STOCK_CODES),
        help="종목코드 (기본: 통신 3사)",
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=RAW_DIR,
        help=f"저장 루트 (기본: {RAW_DIR})",
    )
    args = parser.parse_args()

    driver = _build_driver()
    try:
        for stock_code in args.stock_codes:
            out_dir = collect(stock_code, driver, args.raw_dir)
            print(f"{stock_code}: {out_dir}")
    finally:
        driver.quit()


if __name__ == "__main__":
    main()
