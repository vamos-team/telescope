package com.telescope.backend.user.service;

import com.telescope.backend.global.auth.JwtTokenProvider;
import com.telescope.backend.global.auth.PasswordPolicy;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.user.dto.LoginRequest;
import com.telescope.backend.user.dto.LoginResponse;
import com.telescope.backend.user.dto.SignupRequest;
import com.telescope.backend.user.dto.UserResponse;
import com.telescope.backend.user.entity.User;
import com.telescope.backend.user.entity.UserRole;
import com.telescope.backend.user.repository.UserRepository;
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

    /** 없는 사번일 때도 비교할 해시. 사번이 있을 때와 응답 시간이 같아야 사번 존재 여부를 추측할 수 없다. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtTokenProvider tokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 사번과 비밀번호를 확인하고 토큰을 발급한다. 권한: 누구나(인증 전).
     *
     * @throws BusinessException 사번이 없거나 비밀번호가 틀리면 {@code A001}. 무엇이 틀렸는지 구분하지 않는다
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // BCrypt 상한을 넘으면 인코더가 예외를 던지므로 비교 전에 실패로 처리한다
        if (PasswordPolicy.exceedsMaxBytes(request.password())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        Optional<User> found = userRepository.findByEmployeeNo(request.employeeNo().trim());
        boolean matches = passwordEncoder.matches(request.password(), found.map(User::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        User user = found.get();
        return LoginResponse.of(tokenProvider.issue(user.getId(), user.getRole().name()), UserResponse.from(user));
    }

    /**
     * 회원가입하고 바로 로그인한 것처럼 토큰을 발급한다. 역할은 항상 {@code USER}다. 권한: 누구나(인증 전).
     *
     * @throws BusinessException 이미 있는 사번이면 {@code A005}
     */
    @Transactional
    public LoginResponse signup(SignupRequest request) {
        String employeeNo = request.employeeNo().trim();
        if (userRepository.existsByEmployeeNo(employeeNo)) {
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
