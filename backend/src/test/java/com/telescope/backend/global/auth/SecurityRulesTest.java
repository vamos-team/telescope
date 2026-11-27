package com.telescope.backend.global.auth;

import com.telescope.backend.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig}의 경로 규칙을 실제 보안 설정·DB·JWT로 한곳에서 확인한다(#163).
 * 규칙 출처: docs/backend/api-spec.md 3.2(공개 경로, 오류 코드 표), {@link SecurityConfig}, {@link JwtAuthenticationFilter}.
 *
 * <p>컨트롤러별 테스트에 흩어진 401·403 확인은 그대로 두고, 이 테스트는 규칙 전체를 다시 확인하는 용도다.
 * 동작을 바꾸지 않는다. 규칙과 다른 동작을 찾으면 고치지 않고 이슈에 남긴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class SecurityRulesTest {

    @Autowired MockMvc mvc;

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

    // ---- 도움 함수 ----

    /** 공개 경로 응답: 401(A001)·400·201 중 하나이고, 인증 오류(A002)가 아니다. */
    private void assertPublic(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();

        assertThat(result.getResponse().getStatus()).isIn(201, 400, 401);
        assertThat(errorCodeOrNull(result)).isNotEqualTo("A002");
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
