package com.telescope.auth.user.service;

import com.telescope.common.auth.JwtProperties;
import com.telescope.common.auth.JwtTokenProvider;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.common.exception.RetryAfterException;
import com.telescope.auth.user.dto.LoginRequest;
import com.telescope.auth.user.dto.LoginResponse;
import com.telescope.auth.user.dto.SignupRequest;
import com.telescope.auth.user.entity.User;
import com.telescope.auth.user.entity.UserRole;
import com.telescope.auth.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 로그인(A001, 타이밍 방어, 실패 잠금 연결)과 회원가입(역할 고정, 중복 A005)을 확인한다. */
class AuthServiceTest {

    UserRepository userRepository = Mockito.mock(UserRepository.class);
    // 테스트 속도를 위해 해시 강도를 최소로 둔다
    PasswordEncoder passwordEncoder = Mockito.spy(new BCryptPasswordEncoder(4));
    JwtTokenProvider tokenProvider = new JwtTokenProvider(
            new JwtProperties("unit-test-jwt-secret-key-0123456789abcdef", Duration.ofHours(1)), Clock.systemUTC());
    LoginAttemptService loginAttemptService = Mockito.mock(LoginAttemptService.class);
    AuthService authService;
    User kim;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, tokenProvider,
                new AdminProperties(" A0001 ", "admin-password", null), loginAttemptService);
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

    @Test
    void 회원가입하면_USER로_저장하고_바로_쓸_수_있는_토큰을_준다() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 11L);
            return saved;
        });

        LoginResponse response = authService.signup(
                new SignupRequest(" E2001 ", "password-1", " 박신규 ", " ", "재무기획"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmployeeNo()).isEqualTo("E2001");
        assertThat(saved.getName()).isEqualTo("박신규");
        assertThat(saved.getDept()).isNull();
        assertThat(saved.getJob()).isEqualTo("재무기획");
        assertThat(saved.getRole()).isEqualTo(UserRole.USER);
        assertThat(saved.getPasswordHash()).isNotEqualTo("password-1");
        assertThat(passwordEncoder.matches("password-1", saved.getPasswordHash())).isTrue();

        assertThat(response.user().id()).isEqualTo(11L);
        assertThat(response.user().role()).isEqualTo("USER");
        assertThat(tokenProvider.authenticate(response.accessToken()).role()).isEqualTo("USER");
    }

    @Test
    void 이미_있는_사번으로_가입하면_A005이고_저장하지_않는다() {
        when(userRepository.existsByEmployeeNo("E1001")).thenReturn(true);

        assertError(() -> authService.signup(new SignupRequest("E1001", "password-1", "중복", null, null)),
                ErrorCode.EMPLOYEE_NO_DUPLICATED);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 관리자_사번으로는_가입할_수_없고_A005() {
        // 시드 전에 일반 사용자가 관리자 사번을 먼저 차지하면 관리자가 생기지 않는다
        assertError(() -> authService.signup(new SignupRequest(" A0001 ", "password-1", "선점", null, null)),
                ErrorCode.EMPLOYEE_NO_DUPLICATED);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 동시에_같은_사번으로_가입해_고유_제약에_걸려도_A005() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertError(() -> authService.signup(new SignupRequest("E3001", "password-1", "동시", null, null)),
                ErrorCode.EMPLOYEE_NO_DUPLICATED);
    }

    @Test
    void 로그인에_성공하면_연속_실패_횟수를_되돌리고_실패로_세지_않는다() {
        authService.login(new LoginRequest(" E1001 ", "pw-1234"));

        verify(loginAttemptService).checkNotLocked("E1001");
        verify(loginAttemptService).reset("E1001");
        verify(loginAttemptService, never()).recordFailure(anyString());
    }

    @Test
    void 비밀번호가_틀리면_공백을_뺀_사번으로_실패를_센다() {
        assertLoginFailed(new LoginRequest(" E1001 ", "wrong"));

        verify(loginAttemptService).recordFailure("E1001");
        verify(loginAttemptService, never()).reset(anyString());
    }

    @Test
    void 없는_사번과_72바이트를_넘는_비밀번호도_실패로_센다() {
        assertLoginFailed(new LoginRequest("NONE", "pw-1234"));
        assertLoginFailed(new LoginRequest("E1001", "가".repeat(25)));

        verify(loginAttemptService).recordFailure("NONE");
        verify(loginAttemptService).recordFailure("E1001");
    }

    @Test
    void 잠겨_있으면_비밀번호를_비교하지_않고_A006을_그대로_던진다() {
        doThrow(new RetryAfterException(ErrorCode.LOGIN_LOCKED, 600)).when(loginAttemptService).checkNotLocked("E1001");

        assertError(() -> authService.login(new LoginRequest("E1001", "pw-1234")), ErrorCode.LOGIN_LOCKED);
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(loginAttemptService, never()).recordFailure(anyString());
        verify(loginAttemptService, never()).reset(anyString());
    }

    private void assertLoginFailed(LoginRequest request) {
        assertError(() -> authService.login(request), ErrorCode.LOGIN_FAILED);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }
}
