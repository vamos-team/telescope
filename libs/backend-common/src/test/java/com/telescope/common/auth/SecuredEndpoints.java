package com.telescope.common.auth;

import com.telescope.common.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** SecurityConfig 경로 규칙을 확인하는 더미 엔드포인트. 실제 엔드포인트는 각 서비스에 있다. */
@RestController
class SecuredEndpoints {

    @GetMapping("/api/v1/test/protected")
    ApiResponse<String> protectedGet() {
        return ApiResponse.success("ok");
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/v1/data-sync-runs")
    ApiResponse<String> adminOnly() {
        return ApiResponse.success("ok");
    }

    @PostMapping("/api/v1/auth/login")
    ApiResponse<String> login() {
        return ApiResponse.success("ok");
    }

    @PostMapping("/api/v1/auth/signup")
    ApiResponse<String> signup() {
        return ApiResponse.success("ok");
    }

    @GetMapping("/api/v1/ws")
    ApiResponse<String> ws() {
        return ApiResponse.success("ok");
    }
}
