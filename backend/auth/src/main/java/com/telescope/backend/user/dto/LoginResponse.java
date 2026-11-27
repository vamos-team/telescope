package com.telescope.backend.user.dto;

import com.telescope.backend.global.auth.IssuedToken;

/**
 * 로그인 응답.
 *
 * @param tokenType 항상 {@code Bearer}
 * @param expiresIn 토큰 유효 기간(초)
 */
public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    public static LoginResponse of(IssuedToken token, UserResponse user) {
        return new LoginResponse(token.accessToken(), "Bearer", token.expiresInSeconds(), user);
    }
}
