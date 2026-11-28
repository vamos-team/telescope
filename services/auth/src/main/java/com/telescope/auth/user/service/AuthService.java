package com.telescope.auth.user.service;

import com.telescope.common.auth.JwtTokenProvider;
import com.telescope.common.auth.PasswordPolicy;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.auth.user.dto.LoginRequest;
import com.telescope.auth.user.dto.LoginResponse;
import com.telescope.auth.user.dto.SignupRequest;
import com.telescope.auth.user.dto.UserResponse;
import com.telescope.auth.user.entity.User;
import com.telescope.auth.user.entity.UserRole;
import com.telescope.auth.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 회원가입, 사번·비밀번호 로그인과 액세스 토큰 발급. */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AdminProperties adminProperties;
    private final LoginAttemptService loginAttemptService;

    /** 없는 사번일 때도 비교할 해시. 사번이 있을 때와 응답 시간이 같아야 사번 존재 여부를 추측할 수 없다. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider,
                       AdminProperties adminProperties, LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.adminProperties = adminProperties;
        this.loginAttemptService = loginAttemptService;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 사번과 비밀번호를 확인하고 토큰을 발급한다. 권한: 누구나(인증 전).
     *
     * <p>같은 사번으로 연속해서 정해진 횟수(기본 5)만큼 틀리면 정해진 시간(기본 10분) 동안 잠근다.
     * 없는 사번도 같은 방식으로 센다(#164, docs/backend/auth.md).
     *
     * <p>트랜잭션을 걸지 않는다. 실패 기록은 {@link LoginAttemptService}가 따로 커밋해야 하고,
     * 이 메서드가 트랜잭션을 잡고 있으면 실패 기록이 커넥션을 하나 더 쓰게 된다.
     *
     * @throws BusinessException 사번이 없거나 비밀번호가 틀리면 {@code A001}. 무엇이 틀렸는지 구분하지 않는다.
     *                           잠겨 있으면 {@code A006}(429, {@code Retry-After})이며 비밀번호를 비교하지 않는다
     */
    public LoginResponse login(LoginRequest request) {
        String employeeNo = request.employeeNo().trim();
        loginAttemptService.checkNotLocked(employeeNo);
        // BCrypt 상한을 넘으면 인코더가 예외를 던지므로 비교 전에 실패로 처리한다
        if (PasswordPolicy.exceedsMaxBytes(request.password())) {
            throw loginFailed(employeeNo);
        }
        Optional<User> found = userRepository.findByEmployeeNo(employeeNo);
        boolean matches = passwordEncoder.matches(request.password(), found.map(User::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            throw loginFailed(employeeNo);
        }
        loginAttemptService.reset(employeeNo);
        User user = found.get();
        return LoginResponse.of(tokenProvider.issue(user.getId(), user.getRole().name()), UserResponse.from(user));
    }

    /** 실패를 센 뒤 던질 {@code A001}을 만든다. 이번 실패로 잠겨도 이번 응답은 {@code A001}이고 다음 시도부터 {@code A006}이다. */
    private BusinessException loginFailed(String employeeNo) {
        loginAttemptService.recordFailure(employeeNo);
        return new BusinessException(ErrorCode.LOGIN_FAILED);
    }

    /**
     * 회원가입하고 바로 로그인한 것처럼 토큰을 발급한다. 역할은 항상 {@code USER}다. 권한: 누구나(인증 전).
     *
     * @throws BusinessException 이미 있는 사번이거나 관리자 사번({@code ADMIN_EMPLOYEE_NO})이면 {@code A005}.
     *                           관리자 사번인지 드러내지 않으려고 같은 코드를 쓴다
     */
    @Transactional
    public LoginResponse signup(SignupRequest request) {
        String employeeNo = request.employeeNo().trim();
        if (adminProperties.isAdminEmployeeNo(employeeNo) || userRepository.existsByEmployeeNo(employeeNo)) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NO_DUPLICATED);
        }
        User user = User.create(employeeNo, passwordEncoder.encode(request.password()), request.name().trim(),
                blankToNull(request.dept()), blankToNull(request.job()), UserRole.USER);
        try {
            // 동시에 같은 사번으로 가입하면 위 확인을 둘 다 통과할 수 있다. 고유 제약 위반을 같은 오류로 바꾼다
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EMPLOYEE_NO_DUPLICATED);
        }
        return LoginResponse.of(tokenProvider.issue(user.getId(), user.getRole().name()), UserResponse.from(user));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
