package com.telescope.backend.user.dto;

import com.telescope.backend.user.entity.User;

/**
 * 다른 기능이 사람을 표시할 때 쓰는 최소 정보(#149). 사번·역할은 담지 않는다.
 *
 * @param id   사용자 ID
 * @param name 이름
 * @param dept 부서. 없으면 null
 */
public record UserSummary(Long id, String name, String dept) {

    /** 사용자 엔티티에서 표시 정보만 꺼낸다. */
    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getName(), user.getDept());
    }
}
