package com.telescope.auth.user.dto;

import com.telescope.auth.user.entity.User;

/** 사용자 검색 결과 한 명. 다른 사람의 역할은 알려 주지 않는다(api-spec 3.2). */
public record UserSearchResponse(Long id, String employeeNo, String name, String dept, String job) {

    public static UserSearchResponse from(User user) {
        return new UserSearchResponse(user.getId(), user.getEmployeeNo(), user.getName(), user.getDept(), user.getJob());
    }
}
