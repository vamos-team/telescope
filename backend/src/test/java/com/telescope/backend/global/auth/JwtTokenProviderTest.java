package com.telescope.backend.global.auth;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 토큰 발급·검증과 실패 사유(A002 위조·형식, A003 만료) 구분을 확인한다. */
class JwtTokenProviderTest {

    static final String SECRET = "unit-test-jwt-secret-key-0123456789abcdef";
    static final Instant T0 = Instant.parse("2026-11-26T00:00:00Z");
    static final Duration EXPIRY = Duration.ofHours(1);

    JwtTokenProvider provider(String secret, Instant now) {
        return new JwtTokenProvider(new JwtProperties(secret, EXPIRY), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void 발급한_토큰에서_사용자와_역할을_꺼낸다() {
        IssuedToken issued = provider(SECRET, T0).issue(7L, "ADMIN");

        AuthenticatedUser user = provider(SECRET, T0.plusSeconds(10)).authenticate(issued.accessToken());

        assertThat(user).isEqualTo(new AuthenticatedUser(7L, "ADMIN"));
        assertThat(issued.expiresInSeconds()).isEqualTo(3600);
    }

    @Test
    void STOMP용_검증은_사용자_ID만_돌려준다() {
        String token = provider(SECRET, T0).issue(42L, "USER").accessToken();

        assertThat(provider(SECRET, T0).verify(token)).isEqualTo(42L);
    }

    @Test
    void 만료_직전까지는_통과한다() {
        String token = provider(SECRET, T0).issue(7L, "USER").accessToken();

        assertThat(provider(SECRET, T0.plus(EXPIRY).minusSeconds(1)).authenticate(token).id()).isEqualTo(7L);
    }

    @Test
    void 만료_시각이_되면_A003() {
        String token = provider(SECRET, T0).issue(7L, "USER").accessToken();

        assertError(() -> provider(SECRET, T0.plus(EXPIRY)).authenticate(token), ErrorCode.TOKEN_EXPIRED);
    }

    @Test
    void 다른_키로_서명한_토큰은_A002() {
        String forged = provider("another-secret-key-0123456789abcdef-xyz", T0).issue(7L, "ADMIN").accessToken();

        assertError(() -> provider(SECRET, T0).authenticate(forged), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 위조된_토큰은_만료됐어도_A003이_아니라_A002() {
        String forged = provider("another-secret-key-0123456789abcdef-xyz", T0).issue(7L, "ADMIN").accessToken();

        assertError(() -> provider(SECRET, T0.plus(EXPIRY).plusSeconds(1)).authenticate(forged),
                ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 본문을_고친_토큰은_A002() {
        String[] parts = provider(SECRET, T0).issue(7L, "USER").accessToken().split("\\.");
        String tampered = parts[0] + "." + base64("{\"sub\":\"7\",\"role\":\"ADMIN\",\"exp\":9999999999}") + "." + parts[2];

        assertError(() -> provider(SECRET, T0).authenticate(tampered), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 서명_없는_alg_none_토큰은_A002() {
        String unsigned = base64("{\"alg\":\"none\"}") + "." + base64("{\"sub\":\"7\",\"role\":\"ADMIN\",\"exp\":9999999999}") + ".";

        assertError(() -> provider(SECRET, T0).authenticate(unsigned), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 형식이_틀리거나_빈_토큰은_A002() {
        JwtTokenProvider provider = provider(SECRET, T0);

        assertError(() -> provider.authenticate("not-a-jwt"), ErrorCode.UNAUTHENTICATED);
        assertError(() -> provider.authenticate(" "), ErrorCode.UNAUTHENTICATED);
        assertError(() -> provider.authenticate(null), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 서명_키가_32바이트보다_짧으면_기동하지_않는다() {
        assertThatThrownBy(() -> new JwtProperties("short-secret", EXPIRY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(null, EXPIRY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtProperties(SECRET, Duration.ZERO)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 만료_설정이_비면_1시간이다() {
        assertThat(new JwtProperties(SECRET, null).accessTokenExpiry()).isEqualTo(Duration.ofHours(1));
    }

    private static String base64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
