package com.telescope.backend.user.service;

import com.telescope.backend.global.auth.PasswordPolicy;
import com.telescope.backend.user.entity.User;
import com.telescope.backend.user.entity.UserRole;
import com.telescope.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 서버가 뜰 때 환경변수로 받은 첫 관리자를 만든다. 회원가입으로는 {@code ADMIN}이 생기지 않으므로 관리자는 이 경로로만 생긴다.
 *
 * <ul>
 *   <li>사번·비밀번호가 둘 다 비면 아무것도 하지 않는다.</li>
 *   <li>둘 중 하나만 있거나, 비밀번호가 규칙({@link PasswordPolicy})에 맞지 않거나, 사번·이름이
 *       {@value #MAX_FIELD_LENGTH}자를 넘으면 설정 실수로 보고 기동을 멈춘다.</li>
 *   <li>그 사번이 이미 있으면 건너뛴다. 역할·비밀번호를 바꾸지 않는다. 그 계정이 {@code ADMIN}이 아니면 경고를 남긴다
 *       (관리자 사번을 다른 사람이 먼저 쓰고 있어 관리자가 없는 상태).</li>
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(AdminProperties.class)
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    /** {@code users.employee_no}, {@code users.name} 컬럼 길이. 회원가입 검증({@code SignupRequest})과 같다. */
    static final int MAX_FIELD_LENGTH = 100;

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String employeeNo = properties.employeeNo();
        String password = properties.password();
        boolean hasPassword = password != null && !password.isEmpty();
        if (employeeNo == null && !hasPassword) {
            log.info("관리자 시드 설정이 없어 건너뜁니다.");
            return;
        }
        if (employeeNo == null || !hasPassword) {
            throw new IllegalStateException("ADMIN_EMPLOYEE_NO와 ADMIN_PASSWORD는 함께 설정해야 합니다.");
        }
        if (!PasswordPolicy.isAcceptable(password)) {
            throw new IllegalStateException("ADMIN_PASSWORD는 " + PasswordPolicy.MIN_LENGTH + "자 이상, UTF-8 "
                    + PasswordPolicy.MAX_BYTES + "바이트 이하여야 합니다.");
        }
        String name = properties.name() == null ? AdminProperties.DEFAULT_NAME : properties.name();
        requireMaxLength("ADMIN_EMPLOYEE_NO", employeeNo);
        requireMaxLength("ADMIN_NAME", name);

        Optional<User> existing = userRepository.findByEmployeeNo(employeeNo);
        if (existing.isPresent()) {
            skipExisting(existing.get());
            return;
        }
        User admin = User.create(employeeNo, passwordEncoder.encode(password), name, null, null, UserRole.ADMIN);
        try {
            userRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            // 서버 여러 대가 동시에 뜨면 다른 쪽이 먼저 만들 수 있다. 사번이 정말 생겼을 때만 건너뛰고,
            // 그 밖의 제약 위반은 숨기지 않고 기동을 멈춘다
            Optional<User> raced = userRepository.findByEmployeeNo(employeeNo);
            if (raced.isEmpty()) {
                throw e;
            }
            skipExisting(raced.get());
            return;
        }
        log.info("첫 관리자를 만들었습니다. 사번 {}, id {}", employeeNo, admin.getId());
    }

    private static void requireMaxLength(String envName, String value) {
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new IllegalStateException(envName + "는 " + MAX_FIELD_LENGTH + "자 이하여야 합니다.");
        }
    }

    private static void skipExisting(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            log.info("사번 {}이 이미 있어 관리자 시드를 건너뜁니다.", user.getEmployeeNo());
        } else {
            log.warn("관리자 사번 {}을 {} 계정(id {})이 쓰고 있어 관리자를 만들지 않았습니다. 사번을 바꾸거나 그 계정을 정리해야 합니다.",
                    user.getEmployeeNo(), user.getRole(), user.getId());
        }
    }
}
