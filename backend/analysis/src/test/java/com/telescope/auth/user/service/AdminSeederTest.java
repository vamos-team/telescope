package com.telescope.auth.user.service;

import com.telescope.auth.user.entity.User;
import com.telescope.auth.user.entity.UserRole;
import com.telescope.auth.user.repository.UserRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 첫 관리자 시드: 설정 없음, 새로 만듦, 이미 있음(관리자·일반 사용자), 설정 실수. */
class AdminSeederTest {

    UserRepository userRepository = Mockito.mock(UserRepository.class);
    // 테스트 속도를 위해 해시 강도를 최소로 둔다
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    // 콘솔 출력 형식은 앞서 뜬 Spring 컨텍스트 여부에 따라 달라지므로, 로그 이벤트를 직접 받아 레벨과 메시지를 본다
    Logger seederLogger = (Logger) LoggerFactory.getLogger(AdminSeeder.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void attachLogs() {
        logs.start();
        seederLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogs() {
        seederLogger.detachAppender(logs);
    }

    void seed(String employeeNo, String password, String name) {
        new AdminSeeder(new AdminProperties(employeeNo, password, name), userRepository, passwordEncoder).run(null);
    }

    @Test
    void 설정이_비면_아무것도_만들지_않는다() {
        seed(null, null, null);
        seed(" ", "", "관리자");

        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).findByEmployeeNo(any());
    }

    @Test
    void 사번이_없으면_ADMIN을_만든다() {
        seed(" A0001 ", "admin-password", "운영 관리자");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User admin = captor.getValue();
        assertThat(admin.getEmployeeNo()).isEqualTo("A0001");
        assertThat(admin.getName()).isEqualTo("운영 관리자");
        assertThat(admin.getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(passwordEncoder.matches("admin-password", admin.getPasswordHash())).isTrue();
    }

    @Test
    void 이름을_비우면_관리자로_만든다() {
        seed("A0001", "admin-password", " ");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("관리자");
    }

    @Test
    void 관리자가_이미_있으면_건너뛰고_바꾸지_않는다() {
        when(userRepository.findByEmployeeNo("A0001")).thenReturn(Optional.of(existing(UserRole.ADMIN)));

        seed("A0001", "admin-password", "관리자");

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("이미 있어 관리자 시드를 건너뜁니다");
        });
    }

    @Test
    void 관리자_사번을_일반_사용자가_쓰고_있으면_역할을_바꾸지_않고_경고한다() {
        User squatter = existing(UserRole.USER);
        when(userRepository.findByEmployeeNo("A0001")).thenReturn(Optional.of(squatter));

        seed("A0001", "admin-password", "관리자");

        verify(userRepository, never()).saveAndFlush(any());
        assertThat(squatter.getRole()).isEqualTo(UserRole.USER);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("관리자 사번 A0001을 USER 계정", "관리자를 만들지 않았습니다");
        });
    }

    private User existing(UserRole role) {
        return User.create("A0001", passwordEncoder.encode("someone-else"), "기존", null, null, role);
    }

    @Test
    void 다른_서버가_먼저_만들어_고유_제약에_걸려도_기동은_계속한다() {
        when(userRepository.findByEmployeeNo("A0001"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing(UserRole.ADMIN)));
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatCode(() -> seed("A0001", "admin-password", "관리자")).doesNotThrowAnyException();
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("이미 있어 관리자 시드를 건너뜁니다");
        });
    }

    @Test
    void 제약에_걸렸는데_사번이_없으면_예외를_숨기지_않는다() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("other constraint");
        when(userRepository.findByEmployeeNo("A0001")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertThatThrownBy(() -> seed("A0001", "admin-password", "관리자")).isSameAs(failure);
    }

    @Test
    void 사번이나_이름이_100자를_넘으면_기동하지_않는다() {
        assertThatThrownBy(() -> seed("A".repeat(101), "admin-password", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_EMPLOYEE_NO");
        assertThatThrownBy(() -> seed("A0001", "admin-password", "가".repeat(101)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_NAME");
        assertThatCode(() -> seed("A".repeat(100), "admin-password", "가".repeat(100))).doesNotThrowAnyException();
        verify(userRepository).saveAndFlush(any());
    }

    @Test
    void 사번과_비밀번호_중_하나만_있으면_기동하지_않는다() {
        assertThatThrownBy(() -> seed("A0001", "", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seed(null, "admin-password", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 비밀번호가_규칙에_맞지_않으면_기동하지_않는다() {
        assertThatThrownBy(() -> seed("A0001", "short", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seed("A0001", "        ", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> seed("A0001", "가".repeat(25), null)).isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).saveAndFlush(any());
    }
}
