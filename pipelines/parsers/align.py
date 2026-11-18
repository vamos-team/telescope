"""항목 매칭 — 문서에서 읽은 항목이 후보 항목 중 어느 것과 같은 뜻인지 찾는다.

예: 잠정실적 공시의 "매출액" 칸 → 손익계산서 행 후보 중 "영업수익"
    배당결정 공시의 "배당기준일" 칸 → 기준일 공시 칸 후보 중 "주주명부폐쇄 > 기준일"

판정 단계(사람·LLM)는 없다. 항상 가장 가까운 후보 하나를 고른다. 숫자는 건드리지 않는다.

    from parsers.align import align
    items = [{"path": ["매출액", "당해실적"]}]
    slots = [{"id": "IS-1", "path": ["영업수익"]}, {"id": "IS-2", "path": ["영업이익"]}]
    align(items, slots)    # → [{"slot": "IS-1", "index": 0, "score": 0.83}]

항목·후보는 `path`(라벨 경로, 위 → 아래) 또는 `label`(라벨 하나)을 가진 dict다.

이름이 글자 그대로 같은 항목만 짝짓는 방식은 "매출액"과 "영업수익"처럼 이름이 다른 같은 항목을
찾지 못한다. 그래서 항목 이름을 임베딩해 뜻이 가장 가까운(코사인이 가장 큰) 후보를 고른다.
"""
from __future__ import annotations

import os
import re
from pathlib import Path

DEFAULT_MODEL = "BAAI/bge-m3"   # 파서가 쓰는 임베딩 모델. 환경변수 UNIVERSAL_PARSER_BGE_MODEL로 교체 가능
_NO = re.compile(r"^\s*(\d{1,2}(-\d)?\s*[.)]|[-·]\s)\s*")
_models: dict = {}


def _setting(name: str) -> str:
    """설정값을 환경변수 → 실행 폴더의 .env → 파서 상위 폴더의 .env 순으로 찾는다. 없으면 ""."""
    if os.environ.get(name):
        return os.environ[name]
    for env in (Path(".env"), Path(__file__).resolve().parents[1] / ".env"):
        if env.exists():
            for line in env.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    return ""


def _embed(texts: list[str], model: str):
    if model not in _models:
        import platform
        if platform.system() == "Darwin" and not platform.mac_ver()[0]:
            # 일부 macOS에서 버전 문자열이 비어 huggingface_hub(truststore) 가져오기가 실패하는 문제 회피
            platform.mac_ver = lambda *a, **k: ("14.0", ("", "", ""), "")
        os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
        from sentence_transformers import SentenceTransformer
        # 공개 저장소면 토큰 없이 받는다. 저장소가 승인제(gated)이면 접근 동의를 한 계정의 토큰이 필요하다.
        token = _setting("HF_TOKEN") or None
        try:
            m = SentenceTransformer(model, token=token)
        except Exception as e:
            raise RuntimeError(f"모델 '{model}'을 불러오지 못했습니다 — 인터넷 연결과 모델 이름을 확인하세요. "
                               f"승인제 저장소라면 HF_TOKEN이 필요합니다. ({type(e).__name__})") from e
        m.max_seq_length = 256
        _models[model] = m
    return _models[model].encode(texts, batch_size=32, normalize_embeddings=True, show_progress_bar=False)


def label_text(item: dict) -> str:
    """항목 → 임베딩에 넣을 문장. 라벨 앞 번호('1.', '2-1)', '- ')는 뗀다."""
    path = item.get("path") or ([item["label"]] if item.get("label") else [])
    return " > ".join(_NO.sub("", p).strip() for p in path)


def align(items: list[dict], slots: list[dict], model: str | None = None) -> list[dict]:
    """각 항목에 가장 가까운 후보 하나.

    반환: 항목마다 {"slot": 후보의 id(없으면 None), "index": 후보 위치, "score": 코사인 유사도}.
    후보가 없으면 빈 목록이 아니라 항목마다 slot=None을 돌려준다.
    """
    if not items:
        return []
    if not slots:
        return [{"slot": None, "index": None, "score": None} for _ in items]
    model = model or _setting("UNIVERSAL_PARSER_BGE_MODEL") or DEFAULT_MODEL
    x = _embed([label_text(i) for i in items], model)
    y = _embed([label_text(s) for s in slots], model)
    sim = x @ y.T
    out = []
    for row in sim:
        j = int(row.argmax())
        out.append({"slot": slots[j].get("id"), "index": j, "score": float(row[j])})
    return out
