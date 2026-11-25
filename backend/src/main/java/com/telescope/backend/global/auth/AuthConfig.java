package com.telescope.backend.global.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuthConfig {

    /** 다른 {@link CurrentUserProvider}(JWT 구현, #34)가 없을 때만 쓰는 기본 구현을 등록한다. */
    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    CurrentUserProvider unauthenticatedCurrentUserProvider() {
        return new UnauthenticatedCurrentUserProvider();
    }
}
