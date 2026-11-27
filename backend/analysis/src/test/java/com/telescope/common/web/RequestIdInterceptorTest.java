package com.telescope.common.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RequestIdInterceptorTest {

    private final RestClient.Builder builder = RestClient.builder().requestInterceptor(new RequestIdInterceptor());
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient client = builder.build();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void 지금_요청의_요청_ID를_내부_호출에_넘긴다() {
        MDC.put(RequestIdFilter.MDC_KEY, "trace-1");
        server.expect(header(RequestIdFilter.HEADER, "trace-1")).andRespond(withSuccess());

        client.get().uri("http://auth/x").retrieve().toBodilessEntity();

        server.verify();
    }

    @Test
    void 요청_ID가_없으면_헤더를_붙이지_않는다() {
        server.expect(headerDoesNotExist(RequestIdFilter.HEADER)).andRespond(withSuccess());

        client.get().uri("http://auth/x").retrieve().toBodilessEntity();

        server.verify();
    }
}
