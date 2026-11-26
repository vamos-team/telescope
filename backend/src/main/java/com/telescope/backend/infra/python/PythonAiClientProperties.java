package com.telescope.backend.infra.python;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * ai 서비스 호출 설정({@code infra.python-ai-client}).
 *
 * @param mode               {@code stub}(기본, 가짜 구현) 또는 {@code http}(ai/server 실제 호출)
 * @param baseUrl            ai/server 주소. {@code http} 모드에서 필수 (환경변수 {@code AI_SERVICE_BASE_URL})
 * @param apiKey             {@code X-Internal-Api-Key}로 보낼 공유 비밀키. ai/server의 {@code INTERNAL_API_KEY}와 같은 값.
 *                           {@code http} 모드에서 필수 (환경변수 {@code AI_SERVICE_API_KEY})
 * @param connectTimeout     연결 타임아웃 (ai-contract 2.10 제안 2초)
 * @param startReadTimeout   작업 시작 호출의 읽기 타임아웃. 큐에 올리기만 하므로 짧다 (제안 5초)
 * @param statusReadTimeout  상태 조회의 읽기 타임아웃. 폴링마다 불리므로 더 짧다 (제안 3초)
 */
@ConfigurationProperties(prefix = "infra.python-ai-client")
public record PythonAiClientProperties(
        @DefaultValue("stub") String mode,
        String baseUrl,
        String apiKey,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration startReadTimeout,
        @DefaultValue("3s") Duration statusReadTimeout
) {

    /** 비밀키가 로그나 오류 메시지에 찍히지 않도록 가린다. */
    @Override
    public String toString() {
        return "PythonAiClientProperties[mode=" + mode + ", baseUrl=" + baseUrl + ", apiKey=****"
                + ", connectTimeout=" + connectTimeout + ", startReadTimeout=" + startReadTimeout
                + ", statusReadTimeout=" + statusReadTimeout + "]";
    }
}
