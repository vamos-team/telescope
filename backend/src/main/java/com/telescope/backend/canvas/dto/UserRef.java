package com.telescope.backend.canvas.dto;

/**
 * 응답에서 사람을 가리키는 값. 지금은 ID만 담는다. 이름·부서는 #149에서 user 서비스로 채운다.
 *
 * @param id 사용자 ID
 */
public record UserRef(Long id) {
}
