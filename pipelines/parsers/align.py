"""항목 매칭 — 문서에서 읽은 항목이 후보 항목 중 어느 것과 같은 뜻인지 찾는다.

예: 잠정실적 공시의 "매출액" 칸 → 손익계산서 행 후보 중 "영업수익"
    배당결정 공시의 "배당기준일" 칸 → 기준일 공시 칸 후보 중 "주주명부폐쇄 > 기준일"

판정 단계(사람·LLM)는 없다. 항상 가장 가까운 후보 하나를 고른다. 숫자는 건드리지 않는다.

    from parsers.align import align
    items = [{"doc": "서식: 연결 잠정실적", "path": ["매출액", "당해실적"], "value": "39,813"}]
    slots = [{"id": "IS-1", "doc": "재무제표: 손익계산서", "path": ["영업수익"], "vtype": "number"},
             {"id": "IS-2", "doc": "재무제표: 손익계산서", "path": ["영업이익"], "vtype": "number"}]
    align(items, slots)    # → [{"slot": "IS-1", "index": 0, "score": 0.61}]

항목·후보 dict의 키
  path / label   라벨 경로(위 → 아래) 또는 라벨 하나. 필수
  doc            어느 문서·표의 칸인지 ("서식: 연결 잠정실적", "재무제표: 손익계산서"). 선택
  vtype / value  값 형식(numbers.value_type의 결과) 또는 원문 값(형식을 여기서 판정). 선택
  name           글자 유사도에 쓸 항목 이름 (예: 경로가 "매출액 > 당해실적"이면 "매출액"). 없으면 라벨 경로.
                 "당해실적" 같은 열 머리글이 글자 비교에 섞이면 엉뚱한 행과 겹쳐 보인다. 선택

이름이 글자 그대로 같은 항목만 짝짓는 방식은 "매출액"과 "영업수익"처럼 이름이 다른 같은 항목을
찾지 못한다. 그래서 항목을 임베딩해 뜻이 가장 가까운(코사인이 가장 큰) 후보를 고른다.
라벨만 넣으면 이름이 비슷한 다른 칸("배당기준일"과 "이사회결의일")을 헷갈리므로, 어느 문서의
칸인지(doc)와 값 형식(금액·날짜·글자 등)을 라벨 경로와 함께 한 문장으로 넣는다.

임베딩은 뜻이 가까운 손익 항목(매출액·매출원가·매출총이익)을 서로 가깝게 놓아, 처음 보는 회사에서
"매출액"을 "매출총이익"에 넣곤 한다. 이런 경우 글자가 얼마나 겹치는지가 결정적인 신호라서,
점수 = ALPHA × 글자 유사도 + (1 − ALPHA) × 임베딩 유사도로 섞는다.
"""
from __future__ import annotations

import math
import os
import re
from collections import Counter
from pathlib import Path

import numpy as np

from .numbers import value_type

_VT_KO = {"number": "금액", "percent": "비율", "date": "날짜", "time": "시각", "datetime": "일시",
          "text": "글", "period": "기간", "empty": "빈 값"}
MODEL_ENV = "PARSER_EMBED_MODEL"   # 임베딩 모델은 .env의 설정값으로 정한다
ALPHA = 0.1                     # 글자 유사도 비중. 튜닝에 안 쓴 회사로 나눠 세 번 골라 세 번 모두 0.1
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
            path = model
            if not Path(model).exists() and model.count("/") >= 2:      # "기관/저장소/하위폴더": 그 폴더만 받는다
                from huggingface_hub import snapshot_download
                repo, sub = "/".join(model.split("/")[:2]), "/".join(model.split("/")[2:])
                path = f"{snapshot_download(repo, allow_patterns=[f'{sub}/*'], token=token)}/{sub}"
            m = SentenceTransformer(path, token=token)
        except Exception as e:
            raise RuntimeError(f"모델 '{model}'을 불러오지 못했습니다 — 인터넷 연결과 모델 이름을 확인하세요. "
                               f"승인제 저장소라면 HF_TOKEN이 필요합니다. ({type(e).__name__})") from e
        m.max_seq_length = 256
        _models[model] = m
    return _models[model].encode(texts, batch_size=32, normalize_embeddings=True, show_progress_bar=False)


def label_text(item: dict) -> str:
    """라벨 경로만. 라벨 앞 번호('1.', '2-1)', '- ')는 뗀다."""
    path = item.get("path") or ([item["label"]] if item.get("label") else [])
    return " > ".join(_NO.sub("", p).strip() for p in path)


def item_text(item: dict) -> str:
    """항목 → 임베딩에 넣을 문장: "[서식: 연결 잠정실적] 매출액 > 당해실적 [형식: 금액]".
    doc·값 형식이 없으면 그 부분은 빠진다."""
    path = item.get("path") or ([item["label"]] if item.get("label") else [])
    vt = item.get("vtype") or (value_type(item["value"], path) if "value" in item else "")
    text = label_text(item)
    if item.get("doc"):
        text = f"[{item['doc']}] {text}"
    if vt:
        text = f"{text} [형식: {_VT_KO.get(vt, vt)}]"
    return text


def _char_sim(a: list[str], b: list[str]) -> np.ndarray:
    """글자 유사도: 2~3글자 조각의 TF-IDF 코사인 (소문자, 연속 공백은 하나로). a·b 전체로 IDF를 정한다."""
    def grams(t: str) -> Counter:
        t = re.sub(r"\s\s+", " ", t.lower())
        return Counter(t[i:i + n] for n in (2, 3) for i in range(len(t) - n + 1))

    docs = [grams(t) for t in a + b]
    vocab = {g: k for k, g in enumerate({g for d in docs for g in d})}
    df = Counter(g for d in docs for g in d)
    idf = np.zeros(len(vocab))
    for g, k in vocab.items():
        idf[k] = math.log((1 + len(docs)) / (1 + df[g])) + 1
    m = np.zeros((len(docs), len(vocab)))
    for r, d in enumerate(docs):
        for g, c in d.items():
            m[r, vocab[g]] = c * idf[vocab[g]]
    m /= np.maximum(np.linalg.norm(m, axis=1, keepdims=True), 1e-12)
    return m[: len(a)] @ m[len(a):].T


def align(items: list[dict], slots: list[dict]) -> list[dict]:
    """각 항목에 가장 가까운 후보 하나. 임베딩 모델은 설정값 PARSER_EMBED_MODEL로 정한다.

    반환: 항목마다 {"slot": 후보의 id(없으면 None), "index": 후보 위치,
                   "score": ALPHA × 글자 유사도 + (1 − ALPHA) × 임베딩 코사인}.
    후보가 없으면 빈 목록이 아니라 항목마다 slot=None을 돌려준다.
    """
    if not items:
        return []
    if not slots:
        return [{"slot": None, "index": None, "score": None} for _ in items]
    model = _setting(MODEL_ENV)
    if not model:
        raise RuntimeError(f"임베딩 모델이 설정되지 않았습니다 — 환경변수 또는 .env의 {MODEL_ENV}에 "
                           "모델 이름(저장소, 저장소/하위폴더, 로컬 경로)을 넣으세요. 예시는 .env.example")
    x = _embed([item_text(i) for i in items], model)
    y = _embed([item_text(s) for s in slots], model)
    names = [_NO.sub("", i["name"]).strip() if i.get("name") else label_text(i) for i in items + slots]
    sim = ALPHA * _char_sim(names[: len(items)], names[len(items):]) + (1 - ALPHA) * (x @ y.T)
    out = []
    for row in sim:
        j = int(row.argmax())
        out.append({"slot": slots[j].get("id"), "index": j, "score": float(row[j])})
    return out
