package com.telescope.backend.user.controller;

import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.user.entity.User;
import com.telescope.backend.user.entity.UserRole;
import com.telescope.backend.user.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 보안 설정·DB로 로그인 → 보호 경로 호출 흐름을 확인한다(이슈 #34 완료 기준).
 * 각 테스트는 트랜잭션 안에서 돌고 끝나면 되돌린다.
 */
// K8s 밖에서도 프로브 경로(/actuator/health/liveness·readiness)를 켜서 공개 경로인지 확인한다
@SpringBootTest(properties = "management.endpoint.health.probes.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Transactional
class UserApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.save(User.create("T9001", passwordEncoder.encode("pw-1234"), "통합테스트", "재무팀", "재무기획",
                UserRole.USER));
    }

    @Test
    void 로그인한_토큰으로_본인_정보를_조회한다() throws Exception {
        String token = login("T9001", "pw-1234");

        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.employeeNo").value("T9001"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void 로그인_응답은_토큰_종류와_유효_기간과_사용자를_담는다() throws Exception {
        mvc.perform(loginRequest("T9001", "pw-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.user.name").value("통합테스트"))
                .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 검색_결과에는_역할을_담지_않는다() throws Exception {
        String token = login("T9001", "pw-1234");

        mvc.perform(get("/api/v1/users").param("q", "통합").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].employeeNo").value("T9001"))
                .andExpect(jsonPath("$.data[0].role").doesNotExist());
    }

    @Test
    void 비밀번호가_틀리거나_없는_사번이면_401_A001() throws Exception {
        mvc.perform(loginRequest("T9001", "wrong"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A001"));
        mvc.perform(loginRequest("NOBODY", "pw-1234"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A001"));
    }

    @Test
    void 사번이나_비밀번호가_비면_400_C001() throws Exception {
        mvc.perform(loginRequest("", "pw-1234"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    void 토큰_없이_사용자_API를_부르면_401_A002() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
        mvc.perform(get("/api/v1/users").param("q", "통합"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    @Test
    void 헬스_체크와_K8s_프로브_경로는_토큰_없이_200() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    private String login(String employeeNo, String password) throws Exception {
        String body = mvc.perform(loginRequest(employeeNo, password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private static org.springframework.test.web.servlet.RequestBuilder loginRequest(String employeeNo, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + password + "\"}");
    }
}
