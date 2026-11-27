package com.telescope.backend.user.controller;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.global.response.ApiResponse;
import com.telescope.backend.user.dto.UserSummary;
import com.telescope.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 다른 서비스(canvas)가 사람 이름·부서를 읽는 내부 API. 내부 키로만 부를 수 있다(InternalApiKeyFilter).
 * 없는 사용자는 결과에서 빠진다.
 */
@RestController
@RequestMapping("/internal/v1/users")
@RequiredArgsConstructor
public class InternalUserController {

    static final int MAX_IDS = 500;

    private final UserService userService;

    @GetMapping("/summaries")
    public ApiResponse<List<UserSummary>> summaries(@RequestParam List<Long> ids) {
        if (ids.size() > MAX_IDS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "ids: 한 번에 " + MAX_IDS + "명까지 조회할 수 있습니다");
        }
        return ApiResponse.success(List.copyOf(userService.summaries(ids).values()));
    }
}
