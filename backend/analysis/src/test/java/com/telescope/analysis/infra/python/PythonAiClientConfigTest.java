package com.telescope.analysis.infra.python;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정 {@code infra.python-ai-client.mode}에 따라 구현체가 하나만 골라지는지. */
class PythonAiClientConfigTest {

    ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PythonAiClientConfig.class, PythonAiClientStub.class);

    @Test
    void 모드를_정하지_않으면_가짜_구현을_쓴다() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PythonAiClient.class);
            assertThat(context.getBean(PythonAiClient.class)).isInstanceOf(PythonAiClientStub.class);
        });
    }

    @Test
    void http_모드이고_주소와_비밀키가_있으면_실제_구현을_쓴다() {
        runner.withPropertyValues(
                        "infra.python-ai-client.mode=http",
                        "infra.python-ai-client.base-url=http://ai-server:8000",
                        "infra.python-ai-client.ingest-base-url=http://ingest-worker:8000",
                        "infra.python-ai-client.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasSingleBean(PythonAiClient.class);
                    assertThat(context.getBean(PythonAiClient.class)).isInstanceOf(PythonAiClientHttp.class);
                });
    }

    @Test
    void http_모드인데_주소가_없으면_기동에_실패한다() {
        runner.withPropertyValues("infra.python-ai-client.mode=http", "infra.python-ai-client.api-key=test-key")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("AI_SERVICE_BASE_URL"));
    }

    @Test
    void http_모드인데_비밀키가_없으면_기동에_실패한다() {
        runner.withPropertyValues("infra.python-ai-client.mode=http",
                        "infra.python-ai-client.base-url=http://ai-server:8000",
                        "infra.python-ai-client.ingest-base-url=http://ingest-worker:8000")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("AI_SERVICE_API_KEY"));
    }

    @Test
    void http_모드인데_ingest_주소가_없으면_기동에_실패한다() {
        runner.withPropertyValues("infra.python-ai-client.mode=http",
                        "infra.python-ai-client.base-url=http://ai-server:8000",
                        "infra.python-ai-client.api-key=test-key")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("INGEST_SERVICE_BASE_URL"));
    }

    @Test
    void 설정_출력에_비밀키가_보이지_않는다() {
        PythonAiClientProperties props = new PythonAiClientProperties("http", "http://ai", "http://ingest", "secret-value",
                java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(3));
        assertThat(props.toString()).doesNotContain("secret-value");
    }
}
