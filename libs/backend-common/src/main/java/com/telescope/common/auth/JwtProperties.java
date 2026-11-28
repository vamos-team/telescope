package com.telescope.common.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 액세스 토큰(JWT) 설정. 서명 키는 환경변수({@code JWT_SECRET})로만 주입한다.
 *
 * @param secret            HS256 서명 키. UTF-8 기준 32바이트(256비트) 이상이어야 한다
 * @param accessTokenExpiry 토큰 유효 기간. 기본 1시간(api-spec 10장 제안값)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String secret, Duration accessTokenExpiry) {

    /** HS256은 키가 해시 출력(256비트)보다 짧으면 안전하지 않다(RFC 7518 3.2). */
    static final int MIN_SECRET_BYTES = 32;

    static final Duration DEFAULT_EXPIRY = Duration.ofHours(1);

    public JwtProperties {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret(JWT_SECRET)은 " + MIN_SECRET_BYTES + "바이트 이상이어야 합니다.");
        }
        // 환경변수를 빈 값으로 넘기면(.env의 JWT_ACCESS_TOKEN_EXPIRY=) yml 기본값이 아니라 null이 들어온다
        if (accessTokenExpiry == null) {
            accessTokenExpiry = DEFAULT_EXPIRY;
        }
        if (accessTokenExpiry.isNegative() || accessTokenExpiry.isZero()) {
            throw new IllegalStateException("app.jwt.access-token-expiry는 0보다 커야 합니다.");
        }
    }
}
