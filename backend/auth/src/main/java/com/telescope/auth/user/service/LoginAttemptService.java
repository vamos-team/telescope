package com.telescope.auth.user.service;

import com.telescope.common.exception.ErrorCode;
import com.telescope.common.exception.RetryAfterException;
import com.telescope.auth.user.repository.LoginAttemptRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 사번별 로그인 실패 횟수와 잠금(#164). 규칙은 docs/backend/auth.md.
 *
 * <p>{@link AuthService}와 다른 빈으로 둔다. 같은 클래스 안에서 부르면 트랜잭션 프록시를 거치지 않아
 * 실패 기록이 로그인 실패 예외와 함께 되돌려질 수 있기 때문이다.
 */
@Service
@EnableConfigurationProperties(LoginLockProperties.class)
public class LoginAttemptService {

    private final LoginAttemptRepository repository;
    private final LoginLockProperties properties;
    private final Clock clock;

    public LoginAttemptService(LoginAttemptRepository repository, LoginLockProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 잠겨 있으면 거절한다. 비밀번호를 비교하기 전에 부른다.
     *
     * @throws RetryAfterException 잠겨 있으면 {@code A006}(429). 남은 시간을 {@code Retry-After}로 알린다
     */
    @Transactional(readOnly = true)
    public void checkNotLocked(String employeeNo) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.findById(employeeNo)
                .filter(attempt -> attempt.isLockedAt(now))
                .ifPresent(attempt -> {
                    throw new RetryAfterException(ErrorCode.LOGIN_LOCKED, secondsUntil(now, attempt.getLockedUntil()));
                });
    }

    /**
     * 실패를 한 번 센다. 연속 실패가 설정 횟수에 닿으면 이번 시도부터 잠근다(응답은 A001, 다음 시도부터 A006).
     * 로그인 실패 예외가 나도 남도록 새 트랜잭션에서 바로 커밋한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String employeeNo) {
        LocalDateTime now = LocalDateTime.now(clock);
        repository.recordFailure(employeeNo, properties.maxFailures(), now.plus(properties.lockDuration()), now);
    }

    /** 로그인에 성공하면 연속 실패 횟수를 0으로 되돌린다. */
    @Transactional
    public void reset(String employeeNo) {
        repository.reset(employeeNo, LocalDateTime.now(clock));
    }

    /** 남은 시간을 초 단위로 올림한다. 0.2초 남았어도 1초로 알려 바로 다시 시도해 거절되지 않게 한다. */
    private static long secondsUntil(LocalDateTime now, LocalDateTime until) {
        long millis = Duration.between(now, until).toMillis();
        return (millis + 999) / 1000;
    }
}
