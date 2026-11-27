package com.telescope.analysis.infra.python;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * {@code infra.python-ai-client.mode=http}일 때 ai/server를 실제로 호출하는 {@link PythonAiClientHttp}를 등록한다.
 * 기본({@code stub})은 {@link PythonAiClientStub}이 등록된다.
 */
@Configuration
@EnableConfigurationProperties(PythonAiClientProperties.class)
public class PythonAiClientConfig {

    static final String API_KEY_HEADER = "X-Internal-Api-Key";

    /**
     * 실제 HTTP 구현을 만든다. 시작 호출과 상태 조회는 읽기 타임아웃만 달리한 클라이언트를 쓴다.
     *
     * @throws IllegalStateException 주소나 비밀키가 비어 있을 때. 잘못된 배포 설정을 기동 시점에 알린다
     */
    @Bean
    @ConditionalOnProperty(prefix = "infra.python-ai-client", name = "mode", havingValue = "http")
    PythonAiClient pythonAiClientHttp(PythonAiClientProperties properties) {
        if (!StringUtils.hasText(properties.baseUrl())) {
            throw new IllegalStateException("infra.python-ai-client.mode=http이면 AI_SERVICE_BASE_URL이 필요합니다.");
        }
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("infra.python-ai-client.mode=http이면 AI_SERVICE_API_KEY가 필요합니다.");
        }
        return new PythonAiClientHttp(
                builder(properties, properties.startReadTimeout()).build(),
                builder(properties, properties.statusReadTimeout()).build());
    }

    /** 주소·비밀키 헤더·타임아웃을 갖춘 클라이언트 빌더. 테스트도 같은 설정으로 만든다. */
    static RestClient.Builder builder(PythonAiClientProperties properties, Duration readTimeout) {
        // HTTP/1.1로 고정한다. JDK 기본값(HTTP/2)은 http:// 주소에 h2c 업그레이드를 시도하는데,
        // ai/server(uvicorn)는 업그레이드 요청의 본문을 받지 못해 POST가 422(body 누락)로 거절됐다(로컬 확인).
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(API_KEY_HEADER, properties.apiKey())
                .requestFactory(requestFactory);
    }
}
