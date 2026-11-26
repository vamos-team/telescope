package com.telescope.backend.user.service;

import com.telescope.backend.global.auth.JwtProperties;
import com.telescope.backend.global.auth.JwtTokenProvider;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.user.dto.LoginRequest;
import com.telescope.backend.user.dto.LoginResponse;
import com.telescope.backend.user.entity.User;
import com.telescope.backend.user.entity.UserRole;
import com.telescope.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    UserRepository userRepository = Mockito.mock(UserRepository.class);
    // 테스트 속도를 위해 해시 강도를 최소로 둔다
    PasswordEncoder passwordEncoder = Mockito.spy(new BCryptPasswordEncoder(4));
    JwtTokenProvider tokenProvider = new JwtTokenProvider(
            new JwtProperties("unit-test-jwt-secret-key-0123456789abcdef", Duration.ofHours(1)), Clock.systemUTC());
    AuthService authService;
    User kim;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider);
        kim = User.create("E1001", passwordEncoder.encode("pw-1234"), "김예산", "재무팀", "재무기획", UserRole.ADMIN);
        ReflectionTestUtils.setField(kim, "id", 7L);
        when(userRepository.findByEmployeeNo("E1001")).thenReturn(Optional.of(kim));
        when(userRepository.findByEmployeeNo("NONE")).thenReturn(Optional.empty());
    }

    @Test
    void 맞는_사번과_비밀번호면_토큰과_사용자를_돌려준다() {
        LoginResponse response = authService.login(new LoginRequest(" E1001 ", "pw-1234"));

        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(3600);
        assertThat(response.user().id()).isEqualTo(7L);
        assertThat(response.user().role()).isEqualTo("ADMIN");
        assertThat(tokenProvider.authenticate(response.accessToken()).role()).isEqualTo("ADMIN");
        assertThat(tokenProvider.authenticate(response.accessToken()).id()).isEqualTo(7L);
    }

    @Test
    void 비밀번호가_틀리면_A001() {
        assertLoginFailed(new LoginRequest("E1001", "wrong"));
    }

    @Test
    void 없는_사번이면_A001이고_비밀번호_비교도_한다() {
        assertLoginFailed(new LoginRequest("NONE", "pw-1234"));

        // 사번이 없을 때도 해시 비교를 해야 응답 시간으로 사번 존재 여부를 알 수 없다
        verify(passwordEncoder).matches(eq("pw-1234"), anyString());
    }

    @Test
    void 비밀번호가_72바이트를_넘으면_A001() {
        assertLoginFailed(new LoginRequest("E1001", "가".repeat(25)));
    }

    private void assertLoginFailed(LoginRequest request) {
        assertThatThrownBy(() -> authService.login(request)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.LOGIN_FAILED));
    }
}
