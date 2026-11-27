package com.telescope.common.auth;

/**
 * 발급한 액세스 토큰.
 *
 * @param accessToken      JWT 문자열
 * @param expiresInSeconds 유효 기간(초). 로그인 응답의 {@code expiresIn}
 */
public record IssuedToken(String accessToken, long expiresInSeconds) {
}
