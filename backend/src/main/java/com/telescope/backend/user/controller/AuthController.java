package com.telescope.backend.user.controller;

import com.telescope.backend.global.response.ApiResponse;
import com.telescope.backend.user.dto.LoginRequest;
import com.telescope.backend.user.dto.LoginResponse;
import com.telescope.backend.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 API. 규약은 {@code docs/backend/api-spec.md} 3장. 로그아웃은 서버 호출 없이 화면이 토큰을 버린다. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 사번·비밀번호로 로그인하고 액세스 토큰을 받는다. 권한: 누구나(인증 전). */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }
}
