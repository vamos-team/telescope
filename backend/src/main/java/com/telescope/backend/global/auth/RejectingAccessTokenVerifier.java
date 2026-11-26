package com.telescope.backend.global.auth;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;

/**
 * 토큰 검증 구현 빈이 없을 때의 기본 구현. 어떤 토큰도 믿지 않아 WebSocket 연결도 모두 거절된다.
 */
public class RejectingAccessTokenVerifier implements AccessTokenVerifier {

    /** 항상 인증 실패({@code A002})를 던진다. */
    @Override
    public Long verify(String token) {
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}
