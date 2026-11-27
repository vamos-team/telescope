package com.telescope.common.auth;

import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** {@link JwtAuthenticationFilter}가 채운 요청의 인증 정보에서 사용자 ID를 꺼낸다. */
@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

    /** 인증된 사용자 ID. 인증 정보가 없으면 {@code A002}. */
    @Override
    public Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user.id();
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}
