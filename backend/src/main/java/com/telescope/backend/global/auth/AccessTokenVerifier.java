package com.telescope.backend.global.auth;

/**
 * 액세스 토큰을 검증해 사용자 ID를 돌려준다. HTTP 필터와 WebSocket(STOMP) 연결 인증이 같은 로직을 쓰도록 분리한 자리다.
 *
 * <p>구현은 {@link JwtTokenProvider}다. 구현 빈이 없으면 {@link RejectingAccessTokenVerifier}가 모든 토큰을 거절한다.
 */
public interface AccessTokenVerifier {

    /**
     * 토큰을 검증하고 사용자 숫자 ID를 돌려준다.
     *
     * @param token {@code Bearer } 접두어를 뗀 토큰 문자열
     * @throws com.telescope.backend.global.exception.BusinessException 토큰이 없거나 위조면 {@code A002}, 만료면 {@code A003}
     */
    Long verify(String token);
}
