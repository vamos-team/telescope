package com.telescope.backend.user.service;

import com.telescope.backend.MutableClock;
import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.global.exception.RetryAfterException;
import com.telescope.backend.user.entity.LoginAttempt;
import com.telescope.backend.user.repository.LoginAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 PostgreSQL에서 실패 횟수 세기·잠금·만료·초기화·동시 실패와 트랜잭션 분리를 확인한다(#164).
 * 실패 기록이 따로 커밋되는지 보려고 테스트 자체에는 트랜잭션을 걸지 않고, 매번 테이블을 비운다.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, LoginAttemptServiceTest.ClockOverride.class})
class LoginAttemptServiceTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(LocalDateTime.of(2026, 11, 27, 9, 0));
        }
    }

    @Autowired LoginAttemptService service;
    @Autowired LoginAttemptRepository repository;
    @Autowired MutableClock clock;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        repository.deleteAllInBatch();
        clock.set(LocalDateTime.of(2026, 11, 27, 9, 0));
    }

    @Test
    void 네_번까지는_잠그지_않고_다섯_번째_실패로_10분간_잠근다() {
        fail("E1001", 4);
        assertThatCode(() -> service.checkNotLocked("E1001")).doesNotThrowAnyException();

        fail("E1001", 1);

        LoginAttempt attempt = repository.findById("E1001").orElseThrow();
        assertThat(attempt.getFailedCount()).isEqualTo(5);
        assertThat(attempt.getLockedUntil()).isEqualTo(LocalDateTime.of(2026, 11, 27, 9, 10));
        assertLocked("E1001", 600);
    }

    @Test
    void 잠금_시간이_지나면_풀리고_다시_틀리면_1부터_센다() {
        fail("E1001", 5);
        clock.advance(Duration.ofMinutes(9).plusSeconds(59).plusMillis(200));
        assertLocked("E1001", 1);  // 0.8초 남음 → 올림해서 1초

        clock.advance(Duration.ofMillis(800));  // 정확히 10분
        assertThatCode(() -> service.checkNotLocked("E1001")).doesNotThrowAnyException();

        fail("E1001", 1);
        LoginAttempt attempt = repository.findById("E1001").orElseThrow();
        assertThat(attempt.getFailedCount()).isEqualTo(1);
        assertThat(attempt.getLockedUntil()).isNull();
    }

    @Test
    void 성공하면_횟수를_0으로_되돌려_다시_다섯_번을_틀려야_잠긴다() {
        fail("E1001", 4);
        service.reset("E1001");

        assertThat(repository.findById("E1001").orElseThrow().getFailedCount()).isZero();
        fail("E1001", 4);
        assertThatCode(() -> service.checkNotLocked("E1001")).doesNotThrowAnyException();
        fail("E1001", 1);
        assertLocked("E1001", 600);
    }

    @Test
    void 기록이_없는_사번을_초기화해도_아무것도_만들지_않는다() {
        service.reset("NEVER");

        assertThat(repository.findById("NEVER")).isEmpty();
    }

    @Test
    void 잠긴_동안_더_틀려도_잠금_시각을_늘리지_않는다() {
        fail("E1001", 5);
        clock.advance(Duration.ofMinutes(3));
        fail("E1001", 1);  // 잠금 확인을 동시에 통과한 요청이 늦게 실패를 기록한 경우

        assertThat(repository.findById("E1001").orElseThrow().getLockedUntil())
                .isEqualTo(LocalDateTime.of(2026, 11, 27, 9, 10));
    }

    @Test
    void 바깥_트랜잭션이_되돌려져도_실패_기록은_남는다() {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                service.recordFailure("E1001");
                throw new IllegalStateException("로그인 실패 예외로 바깥 트랜잭션이 되돌려짐");
            });
        } catch (IllegalStateException expected) {
            // 의도한 실패
        }

        assertThat(repository.findById("E1001")).get().extracting(LoginAttempt::getFailedCount).isEqualTo(1);
    }

    @Test
    void 같은_사번으로_동시에_틀려도_횟수가_빠지지_않는다() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                service.recordFailure("RACE1");
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        LoginAttempt attempt = repository.findById("RACE1").orElseThrow();
        assertThat(attempt.getFailedCount()).isEqualTo(threads);
        assertThat(attempt.isLockedAt(clock.now())).isTrue();
    }

    private void fail(String employeeNo, int times) {
        for (int i = 0; i < times; i++) service.recordFailure(employeeNo);
    }

    private void assertLocked(String employeeNo, long retryAfterSeconds) {
        assertThatThrownBy(() -> service.checkNotLocked(employeeNo))
                .isInstanceOfSatisfying(RetryAfterException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOGIN_LOCKED);
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(retryAfterSeconds);
                });
    }
}
