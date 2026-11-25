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

값 형식이 다른 후보는 점수와 상관없이 고르지 않는다(날짜 칸을 글자 칸에 넣지 않는다). 일시는 날짜·시각과
짝이 될 수 있다. 형식을 모르는 항목·후보는 거르지 않는다. 형식이 맞는 후보가 하나도 없으면 slot=None.
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
_vecs: dict[str, dict[str, np.ndarray]] = {}


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
    m = _models[model]
    # 모델이 정해 둔 입력 앞머리(예: arctic "query: ")가 있으면 쓴다 — 모델을 바꿔도 맞게 돌도록
    kw = {"prompt_name": "query"} if (getattr(m, "prompts", None) or {}).get("query") else {}
    # 같은 후보 목록(XBRL 계정·항목 이름표)을 표마다 다시 넣으므로 문장별로 한 번만 계산한다
    cache = _vecs.setdefault(model, {})
    new = list(dict.fromkeys(t for t in texts if t not in cache))
    if new:
        cache.update(zip(new, m.encode(new, batch_size=32, normalize_embeddings=True, show_progress_bar=False, **kw)))
    return np.array([cache[t] for t in texts])


def label_text(item: dict) -> str:
    """라벨 경로만. 라벨 앞 번호('1.', '2-1)', '- ')는 뗀다."""
    path = item.get("path") or ([item["label"]] if item.get("label") else [])
    return " > ".join(_NO.sub("", p).strip() for p in path)


def _vtype(item: dict) -> str:
    """값 형식: 주어진 vtype, 없으면 원문 value에서 판정, 둘 다 없으면 ""(모름)."""
    path = item.get("path") or ([item["label"]] if item.get("label") else [])
    return item.get("vtype") or (value_type(item["value"], path) if "value" in item else "")


def _same_kind(a: str, b: str) -> bool:
    """값 형식이 짝이 될 수 있는가. 모르면(빈 값) 거르지 않는다. 일시는 날짜·시각과도 짝."""
    return not a or not b or a == b or {a, b} in ({"datetime", "date"}, {"datetime", "time"})


def item_text(item: dict) -> str:
    """항목 → 임베딩에 넣을 문장: "[서식: 연결 잠정실적] 매출액 > 당해실적 [형식: 금액]".
    doc·값 형식이 없으면 그 부분은 빠진다."""
    vt = _vtype(item)
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
    df = Counter(g for d in docs for g in d)
    idf = {g: math.log((1 + len(docs)) / (1 + k)) + 1 for g, k in df.items()}
    vecs = []
    for d in docs:
        v = {g: c * idf[g] for g, c in d.items()}
        norm = max(math.sqrt(sum(x * x for x in v.values())), 1e-12)
        vecs.append({g: x / norm for g, x in v.items()})
    # 후보가 수천 개여도 빠르도록 조각 → 후보 색인으로 겹치는 조각만 곱한다
    index: dict[str, list[tuple[int, float]]] = {}
    for j, v in enumerate(vecs[len(a):]):
        for g, x in v.items():
            index.setdefault(g, []).append((j, x))
    out = np.zeros((len(a), len(b)))
    for i, v in enumerate(vecs[: len(a)]):
        for g, x in v.items():
            for j, y in index.get(g, ()):
                out[i, j] += x * y
    return out


def similarity(items: list[dict], slots: list[dict]) -> np.ndarray:
    """항목 × 후보 점수 행렬: ALPHA × 글자 유사도 + (1 − ALPHA) × 임베딩 코사인. 값 형식이 안 맞는 칸은 -inf.
    임베딩 모델은 설정값 PARSER_EMBED_MODEL로 정한다."""
    model = _setting(MODEL_ENV)
    if not model:
        raise RuntimeError(f"임베딩 모델이 설정되지 않았습니다 — 환경변수 또는 .env의 {MODEL_ENV}에 "
                           "모델 이름(저장소, 저장소/하위폴더, 로컬 경로)을 넣으세요. 예시는 .env.example")
    x = _embed([item_text(i) for i in items], model)
    y = _embed([item_text(s) for s in slots], model)
    names = [_NO.sub("", i["name"]).strip() if i.get("name") else label_text(i) for i in items + slots]
    sim = ALPHA * _char_sim(names[: len(items)], names[len(items):]) + (1 - ALPHA) * (x @ y.T)
    sv = [_vtype(s) for s in slots]
    return np.array([np.where([_same_kind(_vtype(i), v) for v in sv], row, -np.inf) for i, row in zip(items, sim)])


def align(items: list[dict], slots: list[dict], one_to_one: bool = False) -> list[dict]:
    """각 항목에 가장 가까운 후보 하나. 임베딩 모델은 설정값 PARSER_EMBED_MODEL로 정한다.

    one_to_one=True: 한 후보를 두 항목에 주지 않는다(한 표의 서로 다른 줄처럼 항목끼리 뜻이 겹치지 않을 때).
    점수가 높은 짝부터 차례로 정하고, 이미 쓴 후보는 다음 항목에 주지 않는다. 후보가 모자라면 남는 항목은 slot=None.
    반환: 항목마다 {"slot": 후보의 id(없으면 None), "index": 후보 위치,
                   "score": ALPHA × 글자 유사도 + (1 − ALPHA) × 임베딩 코사인}.
    후보가 없거나 값 형식이 맞는 후보가 없으면 그 항목은 slot=None.
    """
    none = {"slot": None, "index": None, "score": None}
    if not items:
        return []
    if not slots:
        return [dict(none) for _ in items]
    sim = similarity(items, slots)
    out = [dict(none) for _ in items]
    if one_to_one:
        order = [(i, j) for i, j in zip(*np.unravel_index(np.argsort(-sim, axis=None), sim.shape)) if sim[i, j] > -np.inf]
        used_i, used_j = set(), set()
        for i, j in order:
            if i not in used_i and j not in used_j:
                used_i.add(i); used_j.add(j)
                out[i] = {"slot": slots[j].get("id"), "index": int(j), "score": float(sim[i, j])}
        return out
    for i, row in enumerate(sim):
        j = int(row.argmax())
        if row[j] > -np.inf:
            out[i] = {"slot": slots[j].get("id"), "index": j, "score": float(row[j])}
    return out
