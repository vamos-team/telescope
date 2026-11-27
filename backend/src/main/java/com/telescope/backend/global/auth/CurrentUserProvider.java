package com.telescope.backend.global.auth;

/**
 * 지금 요청을 보낸 인증된 사용자를 알려 준다.
 *
 * <p>기능 패키지는 인증 방식(JWT 등)을 몰라도 이 인터페이스로 사용자 ID를 얻는다.
 * 구현은 JWT 검증 결과를 읽는 {@link SecurityContextCurrentUserProvider}다. 구현 빈이 없으면
 * {@link UnauthenticatedCurrentUserProvider}가 항상 인증 실패를 낸다.
 */
public interface CurrentUserProvider {

    /**
     * 인증된 사용자의 숫자 ID를 돌려준다.
     *
     * @throws com.telescope.backend.global.exception.BusinessException 인증되지 않았으면 {@code A002}
     */
    Long currentUserId();
}
