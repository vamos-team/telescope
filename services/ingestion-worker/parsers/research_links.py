"""수집한 리서치 링크 목록(links.json의 "links") 정리.

수집기는 페이지에 나온 링크를 순서 그대로, 중복까지 남긴다. 여기서 텍스트를 다듬고 URL 기준으로 합친다.
네트워크·DB에 접근하지 않는다.
"""
from __future__ import annotations


def clean_links(links: list[dict]) -> list[dict]:
    """[{"url", "text"}, …] → URL마다 한 건. 순서는 처음 나온 순서를 따른다.

    - text는 앞뒤 공백을 뗀다.
    - URL이 없는 항목은 버린다.
    - 같은 URL이 여러 번 나오면 비어 있지 않은 첫 텍스트를 text로 쓰고,
      그와 다른 텍스트는 버리지 않고 alt_texts에 나온 순서대로 모은다.
    """
    merged: dict[str, dict] = {}
    for link in links:
        url = link.get("url")
        if not url:
            continue
        text = (link.get("text") or "").strip()
        item = merged.setdefault(url, {"url": url, "text": "", "alt_texts": []})
        if not text or text == item["text"] or text in item["alt_texts"]:
            continue
        if not item["text"]:
            item["text"] = text
        else:
            item["alt_texts"].append(text)
    return list(merged.values())
