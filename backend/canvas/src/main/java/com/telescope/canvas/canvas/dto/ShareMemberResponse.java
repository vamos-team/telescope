package com.telescope.canvas.canvas.dto;

import com.telescope.canvas.canvas.entity.CanvasRole;

/**
 * 공유 명단 응답의 한 사람. 요청({@link ShareMember})과 같은 {@code userId}, {@code role}에 표시용 이름·부서를 더했다(#149).
 *
 * @param role edit 또는 view
 * @param name 이름. 사용자가 없으면 null
 * @param dept 부서. 없으면 null
 */
public record ShareMemberResponse(Long userId, CanvasRole role, String name, String dept) {

    /** 명단의 한 사람과 그 사람의 표시 정보를 합친다. */
    public static ShareMemberResponse of(Long userId, CanvasRole role, UserRef person) {
        return new ShareMemberResponse(userId, role, person.name(), person.dept());
    }
}
