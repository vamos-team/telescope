package com.telescope.common.auth;

import com.telescope.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiKeyFilterTest {

    private final List<ErrorCode> written = new ArrayList<>();

    private MockFilterChain run(String configuredKey, String path, String givenKey) throws Exception {
        InternalApiKeyFilter filter = new InternalApiKeyFilter(configuredKey, (res, code) -> written.add(code));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (givenKey != null) {
            request.addHeader(InternalApiKeyFilter.HEADER, givenKey);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return chain;
    }

    @Test
    void 내부_경로에_맞는_키면_통과한다() throws Exception {
        assertThat(run("secret-key", "/internal/v1/users/summaries", "secret-key").getRequest()).isNotNull();
        assertThat(written).isEmpty();
    }

    @Test
    void 키가_없거나_다르면_거절한다() throws Exception {
        assertThat(run("secret-key", "/internal/v1/users/summaries", null).getRequest()).isNull();
        assertThat(run("secret-key", "/internal/v1/users/summaries", "wrong").getRequest()).isNull();
        assertThat(written).containsExactly(ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 키가_설정되지_않았으면_모든_내부_호출을_거절한다() throws Exception {
        assertThat(run("", "/internal/v1/users/summaries", "").getRequest()).isNull();
        assertThat(written).containsExactly(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void 내부_경로가_아니면_검사하지_않는다() throws Exception {
        assertThat(run("", "/api/v1/canvases", null).getRequest()).isNotNull();
        assertThat(written).isEmpty();
    }
}
