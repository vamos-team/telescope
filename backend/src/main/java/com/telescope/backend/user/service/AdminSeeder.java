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

/**
 * 서버가 뜰 때 환경변수로 받은 첫 관리자를 만든다. 회원가입으로는 {@code ADMIN}이 생기지 않으므로 관리자는 이 경로로만 생긴다.
 *
 * <ul>
 *   <li>사번·비밀번호가 둘 다 비면 아무것도 하지 않는다.</li>
 *   <li>둘 중 하나만 있거나 비밀번호가 규칙({@link PasswordPolicy})에 맞지 않으면 설정 실수로 보고 기동을 멈춘다.</li>
 *   <li>그 사번이 이미 있으면 건너뛴다. 역할·비밀번호를 바꾸지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(AdminProperties.class)
@RequiredArgsConstructor
public class AdminSeeder implements ApplicationRunner {

    private final AdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        String employeeNo = trimToNull(properties.employeeNo());
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
        if (userRepository.existsByEmployeeNo(employeeNo)) {
            log.info("사번 {}이 이미 있어 관리자 시드를 건너뜁니다.", employeeNo);
            return;
        }
        String name = trimToNull(properties.name());
        User admin = User.create(employeeNo, passwordEncoder.encode(password),
                name == null ? AdminProperties.DEFAULT_NAME : name, null, null, UserRole.ADMIN);
        try {
            userRepository.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            // 서버 여러 대가 동시에 뜨면 다른 쪽이 먼저 만들 수 있다
            log.info("사번 {}이 이미 있어 관리자 시드를 건너뜁니다.", employeeNo);
            return;
        }
        log.info("첫 관리자를 만들었습니다. 사번 {}, id {}", employeeNo, admin.getId());
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
