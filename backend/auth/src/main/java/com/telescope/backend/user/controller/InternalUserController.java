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
