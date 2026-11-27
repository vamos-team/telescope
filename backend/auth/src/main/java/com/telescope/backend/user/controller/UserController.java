package com.telescope.backend.user.controller;

import com.telescope.backend.global.auth.CurrentUserProvider;
import com.telescope.backend.global.response.ApiResponse;
import com.telescope.backend.user.dto.UserResponse;
import com.telescope.backend.user.dto.UserSearchResponse;
import com.telescope.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 사용자 조회 API. 규약은 {@code docs/backend/api-spec.md} 3장. */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final CurrentUserProvider currentUser;

    /** 본인 정보. 새로고침 뒤 토큰으로 사용자를 복원할 때 쓴다. 권한: 로그인한 본인. */
    @GetMapping("/me")
    public ApiResponse<UserResponse> me() {
        return ApiResponse.success(userService.me(currentUser.currentUserId()));
    }

    /** 사번·이름·부서 일부로 사람을 찾는다(캔버스 공유 초대). 역할은 담지 않는다. 권한: 로그인한 사용자 누구나. */
    @GetMapping
    public ApiResponse<List<UserSearchResponse>> search(@RequestParam(required = false) String q) {
        return ApiResponse.success(userService.search(q));
    }
}
