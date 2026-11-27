package com.telescope.backend.global.auth;

import java.nio.charset.StandardCharsets;

/** 비밀번호 규칙. 회원가입 요청 검증과 관리자 시드가 같은 기준을 쓴다. */
public final class PasswordPolicy {

    /** 최소 길이(글자 수). */
    public static final int MIN_LENGTH = 8;

    /** BCrypt가 해시에 반영하는 최대 길이(UTF-8 바이트). 넘으면 인코더가 예외를 던진다. */
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static boolean exceedsMaxBytes(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /** 비어 있지 않고 최소 길이 이상, 최대 바이트 이하인지. */
    public static boolean isAcceptable(String password) {
        return password != null && !password.isBlank() && password.length() >= MIN_LENGTH && !exceedsMaxBytes(password);
    }
}
