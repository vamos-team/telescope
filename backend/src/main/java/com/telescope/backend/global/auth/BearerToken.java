package com.telescope.backend.global.auth;

/** {@code Authorization} 헤더 값에서 Bearer 토큰을 꺼낸다. HTTP 필터와 STOMP 연결 인증이 같이 쓴다. */
final class BearerToken {

    private static final String SCHEME = "Bearer ";

    private BearerToken() {
    }

    /**
     * {@code Bearer {token}}에서 토큰을 돌려준다. 인증 방식 이름은 대소문자를 가리지 않는다(RFC 7235 2.1).
     *
     * @return 토큰 문자열. 헤더가 없거나 Bearer 형식이 아니거나 토큰이 비었으면 {@code null}
     */
    static String extract(String header) {
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            return null;
        }
        String token = header.substring(SCHEME.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
