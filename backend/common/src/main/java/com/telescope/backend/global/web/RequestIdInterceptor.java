package com.telescope.backend.global.web;

import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/** 내부 서비스를 부를 때 지금 요청의 요청 ID를 {@code X-Request-ID}로 넘긴다. */
public class RequestIdInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String id = MDC.get(RequestIdFilter.MDC_KEY);
        if (id != null) {
            request.getHeaders().set(RequestIdFilter.HEADER, id);
        }
        return execution.execute(request, body);
    }
}
