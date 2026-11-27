package com.telescope.backend.user.service;

import com.telescope.backend.user.entity.User;
import com.telescope.backend.user.entity.UserRole;
import com.telescope.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 첫 관리자 시드: 설정 없음, 새로 만듦, 이미 있음, 설정 실수. */
class AdminSeederTest {

    UserRepository userRepository = Mockito.mock(UserRepository.class);
    // 테스트 속도를 위해 해시 강도를 최소로 둔다
    PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    void seed(String employeeNo, String password, String name) {
        new AdminSeeder(new AdminProperties(employeeNo, password, name), userRepository, passwordEncoder).run(null);
    }

    @Test
    void 설정이_비면_아무것도_만들지_않는다() {
        seed(null, null, null);
        seed(" ", "", "관리자");

        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).existsByEmployeeNo(any());
    }

    @Test
    void 사번이_없으면_ADMIN을_만든다() {
        when(userRepository.existsByEmployeeNo("A0001")).thenReturn(false);

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
    void 사번이_이미_있으면_건너뛰고_바꾸지_않는다() {
        when(userRepository.existsByEmployeeNo("A0001")).thenReturn(true);

        seed("A0001", "admin-password", "관리자");

        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void 다른_서버가_먼저_만들어_고유_제약에_걸려도_기동은_계속한다() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatCode(() -> seed("A0001", "admin-password", "관리자")).doesNotThrowAnyException();
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
