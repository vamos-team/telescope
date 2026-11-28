package com.telescope.research.infra.python;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 연결로 보낸 요청의 모양. {@link PythonAiClientHttpTest}는 요청 팩토리를 바꿔 끼우므로 이 부분을 보지 못한다.
 *
 * <p>JDK 기본(HTTP/2)은 {@code http://}에 h2c 업그레이드를 시도하고, ai/server(uvicorn)는 그 요청의 본문을 받지 못해
 * 작업 시작이 {@code 422}로 거절됐다(로컬 확인). HTTP/1.1로 고정했는지를 JDK 내장 HTTP 서버로 확인한다.
 */
class PythonAiClientWireTest {

    HttpServer server;
    AtomicReference<String> upgradeHeader = new AtomicReference<>();
    AtomicReference<String> body = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/jobs/document-preparation", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"jobId\":\"job-1\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 업그레이드_없이_HTTP_1_1로_본문을_보낸다() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        PythonAiClientProperties props = new PythonAiClientProperties(
                "http", baseUrl, baseUrl, "test-key", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(3));
        PythonAiClient client = new PythonAiClientConfig().pythonAiClientHttp(props);

        JobStartResponse started = client.startDocumentPreparation(new DocumentPreparationRequest("1", "2", "abc.pdf"));

        assertThat(started.jobId()).isEqualTo("job-1");
        assertThat(upgradeHeader.get()).isNull();
        assertThat(body.get()).contains("\"fileStorageKey\":\"abc.pdf\"");
    }
}
