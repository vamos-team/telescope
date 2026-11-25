package com.telescope.backend.canvas.dto;

/**
 * 응답에서 사람을 가리키는 값. 이름·부서는 user 도메인(#34)이 생기면 함께 채운다.
 *
 * @param id 사용자 ID
 */
public record UserRef(Long id) {
}
