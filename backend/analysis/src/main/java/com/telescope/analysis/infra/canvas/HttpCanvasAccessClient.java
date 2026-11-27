package com.telescope.analysis.infra.canvas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.telescope.analysis.document.service.CanvasAccessClient;
import com.telescope.common.auth.InternalApiKeyFilter;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.common.web.RequestIdInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/**
 * 문서가 캔버스 권한을 canvas 서비스의 내부 API로 확인한다.
 * 권한을 확인하지 못하면(응답 없음·오류) 막는다. 모르면 거절한다.
 */
@Slf4j
@Component
public class HttpCanvasAccessClient implements CanvasAccessClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private final RestClient client;

    @Autowired
    public HttpCanvasAccessClient(@Value("${app.canvas-service.base-url}") String baseUrl,
                                  @Value("${app.internal-api-key:}") String apiKey) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(InternalApiKeyFilter.HEADER, apiKey)
                .requestInterceptor(new RequestIdInterceptor())
                .requestFactory(requestFactory)
                .build();
    }

    HttpCanvasAccessClient(RestClient client) {
        this.client = client;
    }

    @Override
    public void requireView(Long canvasId, Long userId) {
        if (roleOf(canvasId, userId) == null) {
            throw new BusinessException(ErrorCode.CANVAS_ACCESS_DENIED);
        }
    }

    @Override
    public void requireEdit(Long canvasId, Long userId) {
        String role = roleOf(canvasId, userId);
        if (role == null) {
            throw new BusinessException(ErrorCode.CANVAS_ACCESS_DENIED);
        }
        if (!canEdit(role)) {
            throw new BusinessException(ErrorCode.CANVAS_EDIT_DENIED);
        }
    }

    /** 이 사람의 캔버스 권한(owner·edit·view). 권한이 없으면 null. 캔버스가 없으면 {@code C002}. */
    private String roleOf(Long canvasId, Long userId) {
        Access access = fetch(canvasId, userId);
        return access == null || !access.active() ? null : access.role();
    }

    private Access fetch(Long canvasId, Long userId) {
        try {
            Envelope body = client.get()
                    .uri("/internal/v1/canvases/{id}/access?userId={userId}", canvasId, userId)
                    .retrieve()
                    .body(Envelope.class);
            return body == null ? null : body.data();
        } catch (RestClientResponseException e) {
            // 캔버스가 없거나 삭제됨(canvas 서비스가 404 C002). 그대로 전한다
            if (e.getStatusCode().value() == 404) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            throw failClosed(canvasId, e);
        } catch (RuntimeException e) {
            throw failClosed(canvasId, e);
        }
    }

    /** 권한을 확인하지 못하면 허용하지 않고 막는다. */
    private BusinessException failClosed(Long canvasId, RuntimeException cause) {
        log.warn("canvas 권한 확인 실패(canvasId={}): {}", canvasId, cause.toString());
        return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static boolean canEdit(String role) {
        String r = role.toLowerCase(Locale.ROOT);
        return r.equals("owner") || r.equals("edit");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Envelope(Access data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Access(boolean active, String role) {
    }
}
