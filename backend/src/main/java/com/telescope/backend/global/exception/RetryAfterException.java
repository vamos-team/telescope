package com.telescope.backend.global.exception;

import lombok.Getter;

/**
 * 일정 시간 뒤에 다시 시도하라는 실패. {@link GlobalExceptionHandler}가 {@code Retry-After} 헤더(초)를 붙인다.
 * 예: 로그인 잠금({@link ErrorCode#LOGIN_LOCKED}).
 */
@Getter
public class RetryAfterException extends BusinessException {

    /** 다시 시도할 수 있을 때까지 남은 초. 1 이상. */
    private final long retryAfterSeconds;

    public RetryAfterException(ErrorCode errorCode, long retryAfterSeconds) {
        super(errorCode);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
