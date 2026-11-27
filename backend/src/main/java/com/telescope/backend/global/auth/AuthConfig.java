package com.telescope.backend.global.auth;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 인증 구현이 빈으로 없을 때 쓰는 안전한 기본 빈. 평소에는 JWT 구현({@link JwtTokenProvider},
 * {@link SecurityContextCurrentUserProvider})이 있어 빠진다. 자동 설정으로 등록해(`META-INF/spring/...AutoConfiguration.imports`)
 * 다른 구현이 빈으로 등록돼 있으면 확실히 빠지게 한다. 일반 설정 클래스의 {@code @ConditionalOnMissingBean}은
 * 평가 순서가 보장되지 않아 두 구현이 함께 등록될 수 있다.
 */
@AutoConfiguration
public class AuthConfig {

    /** 다른 {@link CurrentUserProvider}가 없을 때만 쓰는, 항상 401을 내는 기본 구현을 등록한다. */
    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    CurrentUserProvider unauthenticatedCurrentUserProvider() {
        return new UnauthenticatedCurrentUserProvider();
    }

    /** 다른 {@link AccessTokenVerifier}가 없을 때만 쓰는, 모든 토큰을 거절하는 기본 구현을 등록한다. */
    @Bean
    @ConditionalOnMissingBean(AccessTokenVerifier.class)
    AccessTokenVerifier rejectingAccessTokenVerifier() {
        return new RejectingAccessTokenVerifier();
    }
}
