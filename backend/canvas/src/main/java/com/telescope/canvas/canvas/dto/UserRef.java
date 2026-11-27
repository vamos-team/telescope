package com.telescope.canvas.canvas.dto;

/**
 * 응답에서 사람을 가리키는 값. 이름·부서는 user 서비스에서 읽어 채운다(#149, {@code docs/backend/canvas.md} D14).
 * 사용자가 없으면(삭제 등) {@code name}, {@code dept}는 null이다.
 *
 * @param id   사용자 ID
 * @param name 이름
 * @param dept 부서. 없으면 null
 */
public record UserRef(Long id, String name, String dept) {

    /** 이름을 모르는 사람(사용자가 없거나 아직 읽지 않음). */
    public static UserRef unknown(Long id) {
        return new UserRef(id, null, null);
    }
}
