package com.telescope.common.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BearerTokenTest {

    @Test
    void Bearer_뒤의_토큰을_꺼낸다() {
        assertThat(BearerToken.extract("Bearer abc.def")).isEqualTo("abc.def");
        assertThat(BearerToken.extract("Bearer   abc  ")).isEqualTo("abc");
    }

    @Test
    void 인증_방식_이름은_대소문자를_가리지_않는다() {
        assertThat(BearerToken.extract("bearer abc")).isEqualTo("abc");
        assertThat(BearerToken.extract("BEARER abc")).isEqualTo("abc");
    }

    @Test
    void 형식이_아니거나_토큰이_비면_null() {
        assertThat(BearerToken.extract(null)).isNull();
        assertThat(BearerToken.extract("")).isNull();
        assertThat(BearerToken.extract("Bearer")).isNull();
        assertThat(BearerToken.extract("Bearer ")).isNull();
        assertThat(BearerToken.extract("Bearer    ")).isNull();
        assertThat(BearerToken.extract("Basic abc")).isNull();
        assertThat(BearerToken.extract("Bearerabc")).isNull();
    }
}
