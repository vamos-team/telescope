package com.telescope.common.auth;

import com.telescope.common.CommonTestApplication;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig}의 경로 규칙을 실제 보안 설정·DB·JWT로 한곳에서 확인한다(#163).
 * 규칙 출처: docs/backend/api-spec.md 3.2(공개 경로, 오류 코드 표), {@link SecurityConfig}, {@link JwtAuthenticationFilter}.
 *
 * <p>컨트롤러별 테스트에 흩어진 401·403 확인은 그대로 두고, 이 테스트는 규칙 전체를 다시 확인하는 용도다.
 * 동작을 바꾸지 않는다. 규칙과 다른 동작을 찾으면 고치지 않고 이슈에 남긴다.
 */
@SpringBootTest(classes = CommonTestApplication.class)
@AutoConfigureMockMvc
@Import(SecuredEndpoints.class)
class SecurityRulesTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @Value("${app.jwt.secret}") String secret;

    /** 보호 경로 하나. 토큰 검증 결과만 보려고 어떤 사용자로든 200이 나오는 경로를 쓴다. */
    private static final String PROTECTED = "/api/v1/test/protected";

    // ---- 1. 공개 경로: 토큰 없이 열린다 ----

    /** 헬스 체크는 K8s 프로브가 토큰 없이 부르므로 공개다. */
    @Test
    void 헬스_체크는_토큰_없이_200() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    /** 로그인은 토큰을 받기 전에 부르므로 공개다. 틀린 사번이면 인증 오류(A002)가 아니라 로그인 실패(A001)여야 한다. */
    @Test
    void 로그인은_토큰_없이_불러도_인증_오류가_아니다() throws Exception {
        assertPublic(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"NOBODY-163\",\"password\":\"wrong-password\"}"));
    }

    /** 회원가입은 계정이 없는 사람이 부르므로 공개다. 필수값이 없으면 인증 오류(A002)가 아니라 입력 오류(C001)여야 한다. */
    @Test
    void 회원가입은_토큰_없이_불러도_인증_오류가_아니다() throws Exception {
        assertPublic(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    /** WebSocket 연결 주소는 공개다. 인증은 STOMP CONNECT에서 하므로(CanvasRealtimeTest) 여기서는 401이 아니기만 확인한다. */
    @Test
    void WebSocket_연결_주소는_토큰_없이_불러도_401이_아니다() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/ws")).andReturn();

        assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
        assertThat(errorCodeOrNull(result)).isNotEqualTo("A002");
    }

    // ---- 2. 그 밖의 경로: 토큰이 없으면 401 A002 ----

    /** 공개 경로가 아니면 모두 인증이 필요하다. 기능별로 하나씩 고른다(#148 리뷰: 작업 조회의 401 확인이 따로 없었음). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/api/v1/test/protected"})
    void 공개_경로가_아니면_토큰_없이_부를_때_401_A002(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    // ---- 3. 잘못된 토큰·만료된 토큰 ----

    /** 서명 키가 다른(위조된) 토큰은 거절한다. 만료와 구분해 A002다. */
    @Test
    void 서명이_위조된_토큰이면_401_A002() throws Exception {
        JwtTokenProvider forger = new JwtTokenProvider(
                new JwtProperties("forged-secret-key-not-the-server-0123456789", Duration.ofHours(1)), Clock.systemUTC());

        expectAuthError(bearer(forger.issue(1L, "ADMIN").accessToken()), "A002");
    }

    /** {@code Bearer <토큰>} 형식이 아니면(접두사 없음, 다른 방식, 빈 토큰) 토큰을 보지 않고 거절한다. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RAW", "Token RAW", "Basic dXNlcjpwYXNz", "Bearer "})
    void Bearer_형식이_아니면_401_A002(String header) throws Exception {
        String valid = tokens.issue(1L, "USER").accessToken();

        expectAuthError(header.replace("RAW", valid), "A002");
    }

    /** 만료된 토큰은 A003으로 알려 화면이 다시 로그인을 안내할 수 있게 한다. 서버 키로 두 시간 전에 발급해 만든다. */
    @Test
    void 만료된_토큰이면_401_A003() throws Exception {
        Clock twoHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneId.systemDefault());
        JwtTokenProvider past = new JwtTokenProvider(new JwtProperties(secret, Duration.ofHours(1)), twoHoursAgo);

        expectAuthError(bearer(past.issue(1L, "USER").accessToken()), "A003");
    }

    /** 위 거절이 토큰 때문인지 보려고, 같은 경로에 서버가 발급한 유효한 토큰은 통과하는지 함께 확인한다. */
    @Test
    void 서버가_발급한_유효한_토큰은_통과한다() throws Exception {
        mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, bearer(tokens.issue(1L, "USER").accessToken())))
                .andExpect(status().isOk());
    }

    // ---- 4. 관리자 전용 경로 ----

    /** 재무 데이터 적재 이력은 관리자 전용이다. 인증은 됐지만 역할이 USER면 401이 아니라 403 A004다. */
    @Test
    void 관리자_전용_경로를_USER_토큰으로_부르면_403_A004() throws Exception {
        mvc.perform(get("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, bearer(tokens.issue(1L, "USER").accessToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("A004"));
    }

    /** 같은 경로를 ADMIN 토큰으로 부르면 통과한다. */
    @Test
    void 관리자_전용_경로를_ADMIN_토큰으로_부르면_200() throws Exception {
        mvc.perform(get("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, bearer(tokens.issue(1L, "ADMIN").accessToken())))
                .andExpect(status().isOk());
    }

    // ---- 5. 실패 응답 모양 ----

    /**
     * 보안 필터가 직접 쓰는 실패 응답(401·403)도 컨트롤러 응답과 같은 {@code ApiResponse} 모양이다.
     * {@code data}는 키가 있고 값이 null, {@code error}에 {@code code}와 {@code message}가 있다.
     */
    @Test
    void 인증_인가_실패_응답은_ApiResponse_모양이다() throws Exception {
        assertFailureShape(mvc.perform(get(PROTECTED)).andReturn(), "A002");
        assertFailureShape(mvc.perform(get("/api/v1/data-sync-runs")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokens.issue(1L, "USER").accessToken()))).andReturn(), "A004");
    }

    // ---- 도움 함수 ----

    /** 공개 경로 응답: 401(A001)·400·201 중 하나이고, 인증 오류(A002)가 아니다. */
    /** 공개 경로는 보안 필터를 지나 더미 컨트롤러에 닿아 200이다. 401·403으로 막히면 안 된다. */
    private void assertPublic(RequestBuilder request) throws Exception {
        mvc.perform(request).andExpect(status().isOk());
    }

    private void expectAuthError(String authorization, String code) throws Exception {
        mvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private static void assertFailureShape(MvcResult result, String code) throws Exception {
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = JsonPath.read(result.getResponse().getContentAsString(), "$");
        assertThat(body).containsOnlyKeys("data", "error").containsEntry("data", null);
        Map<String, Object> error = JsonPath.read(result.getResponse().getContentAsString(), "$.error");
        assertThat(error).containsEntry("code", code);
        assertThat(error.get("message")).asString().isNotBlank();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** 응답 본문의 {@code error.code}. JSON이 아니거나 오류가 없으면 {@code null}. */
    private static String errorCodeOrNull(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        try {
            return JsonPath.read(body, "$.error.code");
        } catch (RuntimeException notJsonOrNoError) {
            return null;
        }
    }
}
