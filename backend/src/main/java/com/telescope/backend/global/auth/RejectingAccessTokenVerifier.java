package com.telescope.backend.global.auth;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;

/**
 * 인증 구현(#34)이 붙기 전의 기본 구현. 어떤 토큰도 믿지 않는다.
 * WebSocket 연결도 #34 전까지는 모두 거절된다.
 */
public class RejectingAccessTokenVerifier implements AccessTokenVerifier {

    /** 항상 인증 실패({@code A002})를 던진다. */
    @Override
    public Long verify(String token) {
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}
