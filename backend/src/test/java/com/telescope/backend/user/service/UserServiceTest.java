package com.telescope.backend.user.service;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.user.dto.UserSearchResponse;
import com.telescope.backend.user.dto.UserSummary;
import com.telescope.backend.user.entity.User;
import com.telescope.backend.user.entity.UserRole;
import com.telescope.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    UserRepository userRepository = Mockito.mock(UserRepository.class);
    UserService userService = new UserService(userRepository);

    @Test
    void 본인_정보를_돌려준다() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L, "E1001", "김예산")));

        assertThat(userService.me(7L).employeeNo()).isEqualTo("E1001");
    }

    @Test
    void 토큰의_사용자가_없으면_A002() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.me(99L)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void 여러_사람의_이름_부서를_한_번에_읽고_없는_사용자와_null은_뺀다() {
        when(userRepository.findAllById(any())).thenReturn(List.of(user(7L, "E1001", "김예산")));

        Map<Long, UserSummary> found = userService.summaries(Arrays.asList(7L, 7L, 99L, null));

        assertThat(found).containsOnlyKeys(7L);
        assertThat(found.get(7L).name()).isEqualTo("김예산");
        verify(userRepository, times(1)).findAllById(Set.of(7L, 99L));  // 중복·null을 빼고 한 번만 조회
    }

    @Test
    void 읽을_사람이_없으면_조회하지_않는다() {
        assertThat(userService.summaries(List.of())).isEmpty();

        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void 검색어가_비면_조회하지_않고_빈_목록() {
        assertThat(userService.search(null)).isEmpty();
        assertThat(userService.search("   ")).isEmpty();

        verify(userRepository, never()).search(anyString(), any());
    }

    @Test
    void 검색어_앞뒤_공백을_떼고_최대_6명을_찾는다() {
        when(userRepository.search("김", Limit.of(6))).thenReturn(List.of(user(7L, "E1001", "김예산")));

        List<UserSearchResponse> result = userService.search(" 김 ");

        assertThat(result).extracting(UserSearchResponse::name).containsExactly("김예산");
    }

    private static User user(Long id, String employeeNo, String name) {
        User user = User.create(employeeNo, "hash", name, "재무팀", "재무기획", UserRole.USER);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
