package com.telescope.backend.global.auth;

import com.telescope.backend.global.config.ClockConfig;
import com.telescope.backend.global.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 인증·인가 규칙: 토큰 없음·형식 오류·위조 401 {@code A002}, 만료 401 {@code A003}, 역할 부족 403 {@code A004},
 * 공개 경로는 토큰이 만료돼 있어도 통과. 실제 경로 대신 테스트용 컨트롤러로 규칙만 본다.
 */
@WebMvcTest(controllers = SecurityConfigTest.TestController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, SecurityContextCurrentUserProvider.class, ClockConfig.class,
        SecurityConfigTest.TestController.class})
class SecurityConfigTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokenProvider;
    @Autowired JwtProperties properties;

    @RestController
    static class TestController {

        private final CurrentUserProvider currentUser;

        TestController(CurrentUserProvider currentUser) {
            this.currentUser = currentUser;
        }

        @GetMapping("/api/v1/test/me")
        ApiResponse<Long> me() {
            return ApiResponse.success(currentUser.currentUserId());
        }

        /** 관리자 전용. 권한: ADMIN */
        @PreAuthorize("hasRole('ADMIN')")
        @GetMapping("/api/v1/test/admin")
        ApiResponse<String> admin() {
            return ApiResponse.success("ok");
        }

        @PostMapping("/api/v1/auth/login")
        ApiResponse<String> login() {
            return ApiResponse.success("login");
        }
    }

    @Test
    void 토큰이_없으면_401_A002() throws Exception {
        expectError(mvc.perform(get("/api/v1/test/me")), 401, "A002");
    }

    @Test
    void Bearer_형식이_아니면_401_A002() throws Exception {
        String token = tokenProvider.issue(7L, "USER").accessToken();

        expectError(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Basic " + token)), 401, "A002");
    }

    @Test
    void 위조된_토큰은_401_A002() throws Exception {
        String forged = new JwtTokenProvider(new JwtProperties("another-secret-key-0123456789abcdef-xyz",
                properties.accessTokenExpiry()), Clock.systemUTC()).issue(7L, "ADMIN").accessToken();

        expectError(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged)), 401, "A002");
    }

    @Test
    void 만료된_토큰은_401_A003() throws Exception {
        expectError(mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken())),
                401, "A003");
    }

    @Test
    void 유효한_토큰이면_사용자_ID로_통과한다() throws Exception {
        String token = tokenProvider.issue(7L, "USER").accessToken();

        mvc.perform(get("/api/v1/test/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(7))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    @Test
    void 관리자_전용_기능을_USER가_부르면_403_A004() throws Exception {
        String token = tokenProvider.issue(7L, "USER").accessToken();

        expectError(mvc.perform(get("/api/v1/test/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)),
                403, "A004");
    }

    @Test
    void 관리자_전용_기능을_ADMIN은_부를_수_있다() throws Exception {
        String token = tokenProvider.issue(1L, "ADMIN").accessToken();

        mvc.perform(get("/api/v1/test/admin").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    void 로그인은_토큰_없이도_만료된_토큰이_붙어도_통과한다() throws Exception {
        mvc.perform(post("/api/v1/auth/login")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken()))
                .andExpect(status().isOk());
    }

    private String expiredToken() {
        Instant past = Instant.now().minus(properties.accessTokenExpiry()).minusSeconds(60);
        return new JwtTokenProvider(properties, Clock.fixed(past, ZoneOffset.UTC)).issue(7L, "USER").accessToken();
    }

    private static void expectError(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value(code));
    }
}
