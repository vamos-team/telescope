package com.telescope.backend.user.dto;

import com.telescope.backend.user.entity.User;

/**
 * 로그인한 본인 정보(로그인 응답의 {@code user}, {@code GET /users/me}). 비밀번호 해시는 담지 않는다.
 */
public record UserResponse(Long id, String employeeNo, String name, String dept, String job, String role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmployeeNo(), user.getName(), user.getDept(), user.getJob(),
                user.getRole().name());
    }
}
