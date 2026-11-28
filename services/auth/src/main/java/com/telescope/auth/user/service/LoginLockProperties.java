package com.telescope.auth.user.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 로그인 실패 잠금 설정(#164). 환경변수 {@code AUTH_LOGIN_MAX_FAILURES}, {@code AUTH_LOGIN_LOCK_DURATION}으로 바꿀 수 있다.
 *
 * @param maxFailures  연속으로 이만큼 틀리면 잠근다. 기본 5
 * @param lockDuration 잠그는 시간. 기본 10분
 */
@ConfigurationProperties(prefix = "app.auth.login")
public record LoginLockProperties(Integer maxFailures, Duration lockDuration) {

    static final int DEFAULT_MAX_FAILURES = 5;
    static final Duration DEFAULT_LOCK_DURATION = Duration.ofMinutes(10);

    public LoginLockProperties {
        if (maxFailures == null) maxFailures = DEFAULT_MAX_FAILURES;
        if (lockDuration == null) lockDuration = DEFAULT_LOCK_DURATION;
        if (maxFailures < 1) {
            throw new IllegalArgumentException("app.auth.login.max-failures는 1 이상이어야 합니다: " + maxFailures);
        }
        if (lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("app.auth.login.lock-duration은 0보다 커야 합니다: " + lockDuration);
        }
    }
}
