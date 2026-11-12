"""단위 힌트 — 코드가 단위를 못 찾은 표에 단위를 채워 넣는 자리.

1) bge 임베딩으로 제안받기 (실행 예: examples/bge_hints_example.py)

    res = parse("report.html")
    proposals = suggest_unit_hints(res)                       # 단위 미상 표마다 단위를 제안
    hints = {p["table_no"]: p["unit"] for p in proposals if p["unit"]}
    res2 = parse("report.html", unit_hints=hints)             # 그 표들만 채워진다 (unit_source="hint")

2) 문구로 직접 알려주기 (모든 parse()에 적용)

    set_hints(units={"요약 손익": "억원"}, periods={"당분기": "2025Q3"})

   units  : {표 캡션·머리글 문구: 단위}   단위는 원/천원/백만원/억원/십억원/조원/주/명 등
   periods: {머리글 라벨: 기간}          기간은 2025Q3 / 2025H1 / 2025FY / 2025 / current / prior

힌트는 코드가 단위를 못 찾은 표에만 쓰인다(코드가 찾은 단위를 덮어쓰지 않는다).
숫자 자체는 힌트로 바꿀 수 없다.
"""
from __future__ import annotations

import os
import re
from collections import Counter
from pathlib import Path

_WS = re.compile(r"[\s\xa0\u3000]+")
_state = {"units": {}, "periods": {}}


def key(s: str) -> str:
    return _WS.sub("", str(s or "")).lower()


def set_hints(units: dict | None = None, periods: dict | None = None) -> None:
    _state["units"].update({key(k): v for k, v in (units or {}).items()})
    _state["periods"].update({key(k): v for k, v in (periods or {}).items()})


def clear_hints() -> None:
    _state["units"].clear()
    _state["periods"].clear()


def load() -> dict:
    """tables.py가 읽는 현재 힌트."""
    return _state


# ─────────────────────────────────────────────────────────────────────────────
# bge 임베딩으로 단위 힌트 만들기
#
# 방법: 단위를 못 찾은 표의 "서명"(제목 경로·표 앞 문장·열 머리글·행 이름)을 임베딩하고,
#       같은 문서에서 코드가 단위를 찾은 표 중 서명이 가장 가까운 top_k개의 단위로 투표한다.
#       단위(특히 배율)는 표의 뜻보다 그 문서의 관례로 정해지므로 "같은 문서 안"에서만 찾는다.
#
# 실측 (2026-11, 통신 4사 공시 표 22,648개, 단위 문구를 지우고 맞히기)
#   · 정확도 94.7% (백만원이 아닌 표 77.5%) — 문서 다수결 93.2%, 다른 회사 표를 참고하면 74.1%
#   · 확신도 0.706 이상만 쓰면 표의 89.4%에 제안, 그 안의 오류 1.37%
#   · 위는 기본 모델 yuniju/dart-bge-base로 잰 값이다 (bge-m3를 계정 표기로 미세조정한 모델,
#     실험에 쓴 파일과 sha256 동일). 미세조정 전 BAAI/bge-m3도 거의 같았다(94.7%, 기준 0.704, 오류 0.99%).
#     다른 모델로 바꾸면 유사도 범위가 달라질 수 있으니 MIN_CONFIDENCE를 다시 정할 것
#   · 한 문서 안에서 소수로 쓰인 배율(원·억원·십억원)은 10~50%로 잘 못 맞힌다
# 한계: "이 표는 금액 표가 아니다"라는 답을 할 수 없다. 그래서 파서가 먼저 원화가 아닌 값
#       (비율 행·열, '(단위: tCO2-eQ)', '…수량'·'…주식수' 라벨)을 걸러 내고, 여기에는
#       그러고도 단위 미상으로 남은 표만 온다.
#       실제 단위 미상 표로 확인한 결과 (통신 4사 주석 41개 문서, 기준 이상 제안 42건, 내용으로 판정)
#         맞음 24건(57%)   유동성 만기표·이연법인세 변동·공정가치 수준3 변동 등에 '백만원'
#         틀림  4건(10%)   주식선택권 수량·배출권 수량·주당이익(원) 표에 '백만원'
#         섞임 13건(31%)   '외화 | 원화환산액'처럼 열마다 단위가 다른 표 — 원화 열은 맞고 외화 열은 틀림
#         미상  1건
#       위 94.7%는 "단위가 있는 표의 단위를 가리고" 잰 값이라 실제 쓰임새보다 낙관적이다.
#       결과는 "제안"이다. 자동 확정하지 말고 사람이 보고 고를 것. 특히 열마다 단위가 다른 표는
#       표 단위 힌트로 표현할 수 없으니 적용하지 말 것.
# ─────────────────────────────────────────────────────────────────────────────

DEFAULT_MODEL = "yuniju/dart-bge-base"   # Hugging Face 저장소. 환경변수 UNIVERSAL_PARSER_BGE_MODEL로 교체 가능
MIN_CONFIDENCE = 0.706                 # 위 실측에서 정한 값 (득표 비율 × 최고 유사도)
_UNIT_TXT = re.compile(r"[(（\[]?\s*단\s*위\s*[:：]?[^)）\]]{0,12}[)）\]]?|(천|백만|십억|억|조)\s*원|\d[\d,.]*")
_models: dict = {}


def table_signature(table: dict) -> str:
    """표가 어떤 표인지 알려주는 짧은 글 (숫자와 단위 문구는 뺀다)."""
    def strip(s: str) -> str:
        return _WS.sub(" ", _UNIT_TXT.sub(" ", s or "")).strip()
    rows = list(dict.fromkeys(c["row_label"] for c in table["cells"] if c["row_label"]))[:12]
    cols = list(dict.fromkeys(" ".join(c["col_path"][-2:]) for c in table["cells"] if c["col_path"]))[:12]
    parts = (strip(" > ".join(table["section_path"][-2:])), strip(" ".join(table["context"][:2])),
             "열: " + strip(", ".join(cols)), "행: " + strip(", ".join(rows)))
    return " | ".join(p for p in parts if p)[:400]


def _setting(name: str) -> str:
    """설정값을 환경변수 → 실행 폴더의 .env → 묶음 폴더의 .env 순으로 찾는다. 없으면 ""."""
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
        from sentence_transformers import SentenceTransformer      # pip install sentence-transformers
        # 공개 저장소면 토큰 없이 받는다. 저장소가 승인제(gated)이면 접근 동의를 한 계정의 토큰이 필요하다.
        # HF_TOKEN이 없으면 huggingface-cli login으로 저장된 토큰이 있을 때 그것이 쓰인다(huggingface_hub 기본 동작)
        token = _setting("HF_TOKEN") or None
        try:
            m = SentenceTransformer(model, token=token)
        except Exception as e:
            hint = ("저장소가 승인제(gated)라면 저장소 페이지에서 접근 동의를 한 뒤 .env의 HF_TOKEN에 "
                    "토큰을 넣거나 huggingface-cli login을 하세요. 인터넷 연결과 모델 이름도 확인하세요"
                    if not token and not Path(model).exists()
                    else "토큰의 계정이 이 저장소에 접근 동의를 했는지, 모델 이름이 맞는지 확인하세요")
            raise RuntimeError(f"모델 '{model}'을 불러오지 못했습니다 — {hint}. ({type(e).__name__})") from e
        m.max_seq_length = 256
        _models[model] = m
    return _models[model].encode(texts, batch_size=32, normalize_embeddings=True, show_progress_bar=False)


def suggest_unit_hints(result: dict, model: str | None = None, top_k: int = 3,
                       min_confidence: float = MIN_CONFIDENCE, min_known: int = 3) -> list[dict]:
    """parse() 결과에서 단위를 못 찾은 표마다 단위를 제안한다. 적용 여부는 호출자가 정한다.

    돌려주는 항목: {table_no, unit, candidate, confidence, neighbors, section, n_cells}
      unit        확신도가 min_confidence 이상일 때만 채워진다. 아니면 ""
      candidate   확신도와 무관한 1순위 단위
      confidence  득표 비율 × 최고 유사도 (0~1)
      neighbors   근거가 된 같은 문서의 표 [(표 번호, 단위, 유사도)]
    같은 문서에 코드가 단위를 찾은 표가 min_known개 미만이면 빈 목록을 돌려준다
    (이웃이 한두 개뿐이면 투표가 아니라 그 표의 단위를 그대로 베끼는 것이 된다).
    """
    import numpy as np

    tables = result["tables"]
    known = [t for t in tables if t["unit"] and t["unit_source"] in ("context", "header") and t["cells"]]
    # 대상은 실제로 단위 미상 값이 남은 표만. 비율 행·'(단위: tCO2-eQ)' 같은 원화 아닌 단위는
    # 파서가 이미 처리했으므로 여기 오지 않는다
    todo = [t for t in tables if any(c["note"].startswith("unit_missing") for c in t["cells"])]
    if not todo or len(known) < max(min_known, 1):
        return []
    model = model or _setting("UNIVERSAL_PARSER_BGE_MODEL") or DEFAULT_MODEL
    vecs = _embed([table_signature(t) for t in known + todo], model)
    K, T = vecs[: len(known)], vecs[len(known):]
    out = []
    for t, v in zip(todo, T):
        sims = K @ v
        top = np.argsort(-sims)[:top_k]
        votes = Counter()
        for j in top:
            votes[known[j]["unit"]] += float(sims[j])
        (unit, score), total = votes.most_common(1)[0], sum(votes.values())
        conf = score / total * float(sims[top[0]])
        out.append({"table_no": t["table_no"], "unit": unit if conf >= min_confidence else "",
                    "candidate": unit, "confidence": round(conf, 3),
                    "neighbors": [(known[j]["table_no"], known[j]["unit"], round(float(sims[j]), 3)) for j in top],
                    "section": " > ".join(t["section_path"][-2:]), "n_cells": len(t["cells"])})
    return out
