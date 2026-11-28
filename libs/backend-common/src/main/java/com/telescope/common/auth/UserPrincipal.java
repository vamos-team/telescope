package com.telescope.common.auth;

import java.security.Principal;

/**
 * WebSocket 세션에 붙는 인증된 사용자. 이름은 사용자 ID 문자열이라 {@code convertAndSendToUser}가 이 값으로 보낸다.
 *
 * @param id 사용자 숫자 ID
 */
public record UserPrincipal(Long id) implements Principal {

    /** 개인 알림 주소({@code /user/queue/...})를 찾을 때 쓰는 이름. 사용자 ID 문자열이다. */
    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
