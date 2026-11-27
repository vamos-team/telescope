package com.telescope.auth.user.entity;

import com.telescope.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사번별 연속 로그인 실패 횟수와 잠금 시각(#164). 없는 사번도 같은 방식으로 센다.
 *
 * <p>값은 {@code LoginAttemptRepository}의 원자적 갱신 쿼리로만 바꾼다. 동시에 여러 번 틀려도 횟수가 빠지지 않게 하기 위해서다.
 */
@Getter
@Entity
@Table(name = "login_attempt")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoginAttempt extends BaseTimeEntity {

    @Id
    @Column(name = "employee_no", length = 100)
    private String employeeNo;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    /** 이 시각 전까지 로그인을 막는다. 잠기지 않았으면 {@code null}. */
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** {@code now}에 잠겨 있는지. 잠금 시각이 지났으면 풀린 것으로 본다. */
    public boolean isLockedAt(LocalDateTime now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
