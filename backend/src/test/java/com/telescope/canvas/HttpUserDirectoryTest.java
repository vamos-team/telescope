package com.telescope.canvas;

import com.telescope.backend.canvas.dto.UserRef;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpUserDirectoryTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://auth");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpUserDirectory directory = new HttpUserDirectory(builder.build());

    @Test
    void auth_응답을_이름_표로_바꾼다() {
        server.expect(requestTo("http://auth/internal/v1/users/summaries?ids=1&ids=2"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"data\":[{\"id\":1,\"name\":\"김\",\"dept\":\"재무\"}],\"error\":null}",
                        MediaType.APPLICATION_JSON));

        Map<Long, UserRef> found = directory.find(List.of(1L, 2L));

        assertThat(found).containsExactly(Map.entry(1L, new UserRef(1L, "김", "재무")));
        server.verify();
    }

    @Test
    void auth가_실패하면_한_번_더_시도하고_그래도_실패하면_빈_표() {
        server.expect(ExpectedCount.times(2), requestTo("http://auth/internal/v1/users/summaries?ids=1"))
                .andRespond(withServerError());

        assertThat(directory.find(List.of(1L))).isEmpty();
        server.verify();
    }

    @Test
    void 조회할_ID가_없으면_부르지_않는다() {
        assertThat(directory.find(Arrays.asList((Long) null))).isEmpty();
        server.verify();
    }
}
