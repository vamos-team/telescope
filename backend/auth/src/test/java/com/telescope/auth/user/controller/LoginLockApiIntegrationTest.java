package com.telescope.auth.user.controller;

import com.telescope.auth.AuthApplication;
import com.telescope.common.test.MutableClock;
import com.telescope.common.test.TestcontainersConfig;
import com.telescope.auth.user.entity.User;
import com.telescope.auth.user.entity.UserRole;
import com.telescope.auth.user.repository.LoginAttemptRepository;
import com.telescope.auth.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 보안 설정·DB로 로그인 실패 잠금 응답을 확인한다(#164 완료 기준).
 * 실패 기록은 로그인 실패와 별도로 커밋되어야 하므로 테스트에 트랜잭션을 걸지 않고, 만든 데이터는 끝나면 지운다.
 */
@SpringBootTest(classes = AuthApplication.class)
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, LoginLockApiIntegrationTest.ClockOverride.class})
class LoginLockApiIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(LocalDateTime.of(2026, 11, 27, 9, 0));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserRepository userRepository;
    @Autowired LoginAttemptRepository loginAttemptRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired MutableClock clock;

    @BeforeEach
    void setUp() {
        loginAttemptRepository.deleteAllInBatch();
        clock.set(LocalDateTime.of(2026, 11, 27, 9, 0));
        userRepository.save(User.create("L7001", passwordEncoder.encode("pw-1234"), "잠금테스트", null, null, UserRole.USER));
    }

    @AfterEach
    void tearDown() {
        userRepository.findByEmployeeNo("L7001").ifPresent(userRepository::delete);
        loginAttemptRepository.deleteAllInBatch();
    }

    @Test
    void 다섯_번_틀리면_다섯_번째까지는_401_A001이고_여섯_번째는_맞는_비밀번호도_429_A006() throws Exception {
        failTimes("L7001", 5);

        mvc.perform(login("L7001", "pw-1234"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
                .andExpect(jsonPath("$.error.code").value("A006"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void 십_분이_지나면_다시_로그인할_수_있다() throws Exception {
        failTimes("L7001", 5);
        clock.advance(Duration.ofMinutes(4));
        mvc.perform(login("L7001", "pw-1234"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "360"));

        clock.advance(Duration.ofMinutes(6));
        mvc.perform(login("L7001", "pw-1234")).andExpect(status().isOk());
    }

    @Test
    void 중간에_성공하면_횟수가_0으로_돌아간다() throws Exception {
        failTimes("L7001", 4);
        mvc.perform(login("L7001", "pw-1234")).andExpect(status().isOk());

        failTimes("L7001", 4);
        mvc.perform(login("L7001", "pw-1234")).andExpect(status().isOk());
    }

    @Test
    void 없는_사번도_같은_횟수와_같은_응답으로_잠긴다() throws Exception {
        failTimes("NOBODY-164", 5);

        mvc.perform(login("NOBODY-164", "pw-1234"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "600"))
                .andExpect(jsonPath("$.error.code").value("A006"));
    }

    @Test
    void 사번_앞뒤_공백은_같은_사번으로_센다() throws Exception {
        failTimes(" L7001", 2);
        failTimes("L7001 ", 3);

        mvc.perform(login("L7001", "pw-1234")).andExpect(status().isTooManyRequests());
    }

    @Test
    void 다른_사번은_영향을_받지_않는다() throws Exception {
        failTimes("NOBODY-164", 5);

        mvc.perform(login("L7001", "pw-1234")).andExpect(status().isOk());
    }

    private void failTimes(String employeeNo, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mvc.perform(login(employeeNo, "wrong-password"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("A001"));
        }
    }

    private static RequestBuilder login(String employeeNo, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeNo\":\"" + employeeNo + "\",\"password\":\"" + password + "\"}");
    }
}
