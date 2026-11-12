"""숫자·단위·기간 판별 — 회사·문서 형식과 무관한 공용 규칙."""
from __future__ import annotations

import re
from typing import Optional

_WS = re.compile(r"[\s\xa0　]+")


def clean(s: object) -> str:
    if s is None:
        return ""
    return _WS.sub(" ", str(s)).strip()


# ─── 숫자 ────────────────────────────────────────────────────

_NUM_RE = re.compile(
    r"^(?P<neg1>[-−–△▽(（])?\s*(?P<cur>[₩$€¥])?\s*"
    r"(?P<num>\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?|\.\d+)"
    r"\s*(?P<pct>%|％|%p|pp)?\s*(?P<neg2>[)）])?\s*(?P<note>\(\*\d*(?:,\s*\d+)*\))?$"
)
_DASH_ONLY = re.compile(r"^[-−–—]+$")


def parse_number(text: str) -> tuple[Optional[float], str]:
    """'(1,234)' → (-1234, ''), '△5' → (-5, ''), '12.3%' → (12.3, '%').

    '-'·'—' 단독은 0으로 본다(재무표 관례). 숫자가 아니면 (None, '').
    """
    t = clean(text).replace(" ", "")
    if not t:
        return None, ""
    if _DASH_ONLY.match(t):
        return 0.0, ""
    m = _NUM_RE.match(t)
    if not m:
        return None, ""
    v = float(m.group("num").replace(",", ""))
    n1, n2 = m.group("neg1") or "", m.group("neg2") or ""
    if n1 in ("(", "（"):
        if not n2:
            return None, ""   # 짝 안 맞는 괄호 = 각주 등
        neg = True
    elif n1:
        neg = True            # - − – △ ▽
    else:
        if n2:
            return None, ""
        neg = False
    pct = m.group("pct") or ""
    return (-v if neg else v), ("%" if pct in ("%", "％") else ("%p" if pct else ""))


def is_numeric(text: str) -> bool:
    v, _ = parse_number(text)
    return v is not None and bool(re.search(r"\d", text or ""))


# ─── 단위 ────────────────────────────────────────────────────

_UNIT_MULT = {
    "원": 1.0, "천원": 1e3, "만원": 1e4, "백만원": 1e6, "천만원": 1e7,
    "억원": 1e8, "십억원": 1e9, "백억원": 1e10, "천억원": 1e11, "조원": 1e12,
    "krw": 1.0, "krw thousand": 1e3, "krw million": 1e6, "krw billion": 1e9,
    "주": 1.0, "천주": 1e3, "명": 1.0, "%": 1.0,
}
_UNIT_RE = re.compile(
    r"단\s*위\s*[:：]?\s*(?P<u>(?:천|백|십|만)?(?:만|억|조)?\s*원|천\s*주|주|명|%|"
    r"KRW\s*(?:thousand|million|billion)?|(?:thousands?|millions?|billions?)\s+of\s+(?:Korean\s+)?won)",
    re.IGNORECASE,
)
_UNIT_LOOSE = re.compile(r"[(（]\s*(?P<u>(?:천|백|십)?(?:억|조)?\s*원|백만원|십억원|억원|조원)\s*[)）]")


def _norm_unit(u: str) -> str:
    u = clean(u).lower().replace(" ", "")
    m = re.match(r"(thousands?|millions?|billions?)ofkorean?won|(thousands?|millions?|billions?)ofwon", u)
    if m:
        w = (m.group(1) or m.group(2)).rstrip("s")
        return {"thousand": "천원", "million": "백만원", "billion": "십억원"}[w]
    if u.startswith("krw"):
        return {"krw": "원", "krwthousand": "천원", "krwmillion": "백만원", "krwbillion": "십억원"}.get(u, "원")
    return u


# 원화가 아닌 단위 — 금액이 아니라는 사실 자체가 정보다 ("단위 미상"으로 남기지 않는다)
#   '(단위: tCO2-eQ)', '단위 : MW' 처럼 '단위:' 뒤에 오는 임의의 단위 낱말
#   단위 낱말은 쉼표·닫는 괄호·글 끝으로 끝나야 한다('단위: 다음과 같이 …' 같은 문장은 제외)
_UNIT_OTHER = re.compile(
    r"단\s*위\s*[:：]\s*(?P<u>[^\s,，)）\]\[(（\d][^,，)）\]\[(（]{0,19}?)\s*(?P<end>[,，]|[)）\](（]|$)")
#   라벨 끝의 '(건)', '(시간)' 같은 세는 단위. '(주)'는 주식회사 표기·각주와 겹쳐 넣지 않는다
#   '(톤(tCO2-eq))'처럼 괄호 안에 설명이 한 번 더 붙은 것도 본다
_UNIT_COUNT = re.compile(
    r"[(（]\s*(?P<u>명|건|회|개|시간|톤|회선|가구|대|배|개월)\s*(?:[(（][^()（）]*[)）])?\s*[)）]\s*$")
# 라벨이 '무엇을 세는지'를 말해 주는 경우: 주식수·좌수 → 주, 수량·건수·횟수 등 → 수량 (금액이 아님)
_QTY_SHARES = re.compile(r"주식\s*수|주식의\s*수|좌\s*수")
_QTY_COUNT = re.compile(r"수\s*량|건\s*수|횟\s*수|인원\s*수|가입자\s*수|회선\s*수|세대\s*수|가구\s*수")


def quantity_unit(label: str) -> str:
    """'가중평균유통보통주식수' → '주', '무상할당배출권 수량' → '수량'. 해당 없으면 ''."""
    t = clean(label)
    if _QTY_SHARES.search(t):
        return "주"
    if _QTY_COUNT.search(t):
        return "수량"
    return ""
MONEY_UNITS = frozenset(u for u in _UNIT_MULT if u.endswith("원"))


def is_money_unit(unit: str) -> bool:
    """원화 금액 단위인가 (원·천원·백만원·억원·십억원·조원 …)."""
    return unit in MONEY_UNITS


def detect_unit(texts: list[str]) -> tuple[str, float]:
    """캡션·주변 문단에서 단위를 찾는다. 못 찾으면 ('', 1.0) — 호출자가 판단.

    가까운 글부터 본다. 한 글 안에서는 원화·주·명 단위 → 그 밖의 단위('단위: tCO2-eQ', '(건)') 순.
    원화가 아닌 단위는 배수 1로 돌려준다(환산하지 않는다는 뜻).
    """
    for t in texts:
        t = t or ""
        m = _UNIT_RE.search(t) or _UNIT_LOOSE.search(t)
        if m:
            u = _norm_unit(m.group("u"))
            if u in _UNIT_MULT:
                return u, _UNIT_MULT[u]
        m = _UNIT_OTHER.search(t)
        if m:
            if m.group("end") in (",", "，"):
                # '(단위: 개, 백만원)'처럼 단위가 여럿이면 어느 열이 어느 단위인지 알 수 없다 → 미상으로 둔다
                return "", 1.0
            u = _WS.sub("", m.group("u"))
            if len(u) <= 10:                 # 단위 낱말은 짧다 — 긴 것은 문장이다
                return u, 1.0
        m = _UNIT_COUNT.search(clean(t))
        if m:
            return m.group("u"), 1.0
    return "", 1.0


# ─── 기간 ────────────────────────────────────────────────────

_PERIOD_PATTERNS: list[tuple[re.Pattern, callable]] = [
    # 1Q24, 4Q 2024, 24.3Q, 3Q'25
    (re.compile(r"(?<!\d)([1-4])\s*Q\s*'?\s*(\d{2}|\d{4})(?!\d)", re.I),
     lambda m: f"{_yy(m.group(2))}Q{m.group(1)}"),
    (re.compile(r"(?<!\d)(\d{2})\s*\.\s*([1-4])\s*Q", re.I),
     lambda m: f"{_yy(m.group(1))}Q{m.group(2)}"),
    (re.compile(r"(20\d{2})\s*년?\s*([1-4])\s*분기"), lambda m: f"{m.group(1)}Q{m.group(2)}"),
    (re.compile(r"(20\d{2})\s*년?\s*(상반기|반기|1H|H1)", re.I), lambda m: f"{m.group(1)}H1"),
    (re.compile(r"(20\d{2})\s*년?\s*(하반기|2H|H2)", re.I), lambda m: f"{m.group(1)}H2"),
    (re.compile(r"(?:FY|’|')\s*(\d{2}|\d{4})(?!\d)\s*(?:년)?\s*(?:연간|전체)?", re.I),
     lambda m: f"{_yy(m.group(1))}FY"),
    (re.compile(r"(20\d{2})\s*[.\-/]\s*(\d{1,2})\s*[.\-/]\s*(\d{1,2})"),
     lambda m: f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"),
    (re.compile(r"제\s*(\d+)\s*\(?(당|전)?\s*\)?\s*기"), lambda m: f"제{m.group(1)}기"),
    (re.compile(r"(?<!\d)(20\d{2})\s*년?(?!\d)"), lambda m: f"{m.group(1)}"),
]
_REL_PERIOD = [
    ("전전기", "prior2"), ("전기", "prior"), ("당기", "current"), ("당분기", "current"),
    ("전분기", "prior_q"), ("당반기", "current"), ("전반기", "prior"),
]


def _yy(s: str) -> str:
    return s if len(s) == 4 else f"20{s}"


_SPACED = re.compile(r"(?<![가-힣])([가-힣]) (?=[가-힣](?![가-힣]))")


def unspace(text: str) -> str:
    """'기 초'·'취 득'·'상 각' 같은 자간 띄어쓰기를 붙인다 ('처분 및 폐기'는 유지)."""
    prev = None
    t = clean(text)
    while prev != t:
        prev, t = t, _SPACED.sub(r"\1", t)
    return t


def detect_period(text: str) -> str:
    """머리글·문맥 텍스트에서 기간 라벨. 상대(당기/전기)가 절대보다 우선.

    한 문장에 둘 다 있으면('당기 및 전기 중 … 변동내역 ① 당기') 마지막에 나온 쪽.
    """
    t = unspace(text)
    if not t:
        return ""
    best = None
    for kw, lab in _REL_PERIOD:
        # 뒤에 한글이 이어지면 다른 단어('전기통신설비', '당기손익') — 초/말만 허용
        for m in re.finditer(rf"(?<![가-힣]){kw}(?=$|[^가-힣]|초|말)", t):
            if best is None or m.start() > best[0]:
                best = (m.start(), lab)
    if best:
        return best[1]
    for pat, fn in _PERIOD_PATTERNS:
        m = pat.search(t)
        if m:
            return fn(m)
    return ""


# ─── 비율·표시 배율 (엑셀) ───────────────────────────────────

# 라벨의 중심 낱말이 비율인 행. 뒤에 ', 세전'·'(연)' 같은 꼬리가 붙어도 인정한다. 환율은 비율이 아니다
_RATIO_LABEL = re.compile(
    r"((?<!환)율|률|비율|마진|비중|margin|ratio|\brate|%)\s*([,，(（\[][^,，]{0,12}[)）\]]?)?\s*$", re.I)


def is_ratio_label(label: str) -> bool:
    """'영업이익률', 'EBITDA 마진', '부채비율(%)' 같은 비율 행인지."""
    return bool(_RATIO_LABEL.search(clean(label)))


def display_scale(number_format: str) -> float:
    """표시 형식 끝의 콤마 개수 → 화면 축소 배율. '#,##0,' → 1000, '#,##0,,' → 1e6.

    형식이 '양수;음수;0' 구역으로 나뉘면 첫 구역만 본다. 따옴표 안 문자는 무시.
    """
    if not number_format:
        return 1.0
    sec = re.sub(r'"[^"]*"', "", number_format.split(";")[0])
    m = re.search(r"[0#?](,+)(?=[^0#?,]*$)", sec)
    return 1000.0 ** len(m.group(1)) if m else 1.0


# ─── 한국어 금액·증감 표현 (문장 속) ─────────────────────────

_NUMTOK = r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"
_KR_AMOUNT = re.compile(
    rf"(?P<neg>[-−–△▽])?\s*(?P<body>(?:(?:{_NUMTOK})\s*(?:조|억|천만|백만|십억|만|천)\s*)+)(?P<won>원|달러)?"
)
_KR_PART = re.compile(rf"({_NUMTOK})\s*(조|십억|억|천만|백만|만|천)")
_KR_PART_MULT = {"조": 1e12, "십억": 1e9, "억": 1e8, "천만": 1e7, "백만": 1e6, "만": 1e4, "천": 1e3}
_PCT = re.compile(rf"(?P<neg>[-−–△▽]|\+)?\s*(?P<num>{_NUMTOK})\s*(?P<u>%p|%포인트|퍼센트포인트|pp|bp|bps|%|％|퍼센트)", re.I)
_DOWN_WORDS = re.compile(r"(감소|하락|축소|줄|떨어|역성장|적자|손실|하회)")


def normalize_kr_amount(text: str) -> list[dict]:
    """문장에서 금액·비율 표현을 모두 찾아 표준값으로.

    '2조 9,000억원' → {kind:'KRW', value:2.9e12}, '1,200억원' → 1.2e11,
    '30bp' → {kind:'%p', value:0.3}, 'YoY +2.1%' → {kind:'%', value:2.1},
    '△5.3%' → -5.3. '전년 대비 2.1% 감소'처럼 방향이 낱말로 표현되면 direction='down'을
    붙이되 부호는 바꾸지 않는다(원문 숫자 그대로 보존 — 해석은 호출자).
    반환 항목: kind, value, text(원문 조각), start, end, direction
    """
    t = clean(text)
    out: list[dict] = []
    taken: list[tuple[int, int]] = []
    for m in _KR_AMOUNT.finditer(t):
        parts = _KR_PART.findall(m.group("body"))
        if not parts:
            continue
        v = sum(float(n.replace(",", "")) * _KR_PART_MULT[u] for n, u in parts)
        if m.group("neg"):
            v = -v
        out.append({"kind": "USD" if m.group("won") == "달러" else "KRW", "value": v,
                    "text": m.group(0).strip(), "start": m.start(), "end": m.end()})
        taken.append((m.start(), m.end()))
    for m in _PCT.finditer(t):
        if any(a <= m.start() < b for a, b in taken):
            continue
        v = float(m.group("num").replace(",", ""))
        u = m.group("u").lower()
        if u in ("bp", "bps"):
            v, kind = v / 100.0, "%p"
        elif u in ("%p", "%포인트", "퍼센트포인트", "pp"):
            kind = "%p"
        else:
            kind = "%"
        if m.group("neg") and m.group("neg") != "+":
            v = -v
        out.append({"kind": kind, "value": v, "text": m.group(0).strip(),
                    "start": m.start(), "end": m.end()})
    out.sort(key=lambda d: d["start"])
    for d in out:
        tail = re.split(r"[,，;·]", t[d["end"]:d["end"] + 10])[0]   # 같은 구절 안에서만 방향 낱말을 찾음
        d["direction"] = "down" if _DOWN_WORDS.search(tail) else ("up" if re.search(r"(증가|상승|확대|성장|늘|개선|상회)", tail) else "")
    return out
