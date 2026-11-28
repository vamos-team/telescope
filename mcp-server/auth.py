import secrets

from fastapi import Header, HTTPException, Request, status


def verify_internal_api_key(
    request: Request,
    x_internal_api_key: str | None = Header(default=None),
) -> None:
    """서비스끼리만 부르는 내부 API를 공유 비밀키로 확인한다. ai/server와 같은 방식."""
    expected: str = request.app.state.settings.internal_api_key
    if x_internal_api_key is None or not secrets.compare_digest(
        x_internal_api_key.encode(), expected.encode()
    ):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="인증에 실패했습니다.")
