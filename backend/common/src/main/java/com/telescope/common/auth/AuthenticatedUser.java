package com.telescope.common.auth;

/**
 * 검증된 액세스 토큰이 가리키는 사용자. HTTP 요청의 인증 정보({@code SecurityContext})에 담긴다.
 *
 * @param id   사용자 숫자 ID
 * @param role 서비스 전체 역할({@code ADMIN} / {@code USER}). 토큰을 발급할 때의 값이다
 */
public record AuthenticatedUser(Long id, String role) {
}
