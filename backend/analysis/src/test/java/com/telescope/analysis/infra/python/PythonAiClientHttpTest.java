package com.telescope.analysis.infra.python;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ai/server 호출 규약(경로, 헤더, 요청 본문, 응답 변환, 오류 구분). 실제 ai/server 대신
 * {@link MockRestServiceServer}로 응답을 흉내 낸다. 타임아웃은 로컬 확인으로 본다.
 */
class PythonAiClientHttpTest {

    private static final String BASE = "http://ai-server:8000";
    private static final String KEY = "test-key";

    MockRestServiceServer server;
    PythonAiClientHttp client;

    @BeforeEach
    void setUp() {
        PythonAiClientProperties props = new PythonAiClientProperties(
                "http", BASE, BASE, KEY, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(3));
        RestClient.Builder builder = PythonAiClientConfig.builder(props.baseUrl(), props, props.startReadTimeout());
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        // 에이전트·ingest 모두 같은 mock으로 둔다(이 테스트는 호출 형식만 본다). 종류별 주소 분기는 getJobStatus에 JobType으로 전달한다
        client = new PythonAiClientHttp(restClient, restClient, restClient, restClient);
    }

    @Test
    void 자료_준비_시작은_경로_헤더_본문을_규약대로_보내고_jobId를_돌려준다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/document-preparation"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", KEY))
                .andExpect(content().json("""
                        {"documentId":"1","documentVersionId":"2","fileStorageKey":"abc.pdf"}""", JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"jobId\":\"job-1\"}"));

        JobStartResponse started = client.startDocumentPreparation(new DocumentPreparationRequest("1", "2", "abc.pdf"));

        assertThat(started.jobId()).isEqualTo("job-1");
        server.verify();
    }

    @Test
    void 분석_시작은_기준_시점을_날짜_문자열로_보낸다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/analysis"))
                .andExpect(content().json("""
                        {"analysisRunId":"run-1","allowedDocumentVersionIds":["v1","v2"],"asOfDate":"2026-06-30"}"""))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"jobId\":\"job-2\"}"));

        JobStartResponse started = client.startAnalysis(
                new AnalysisRequest("run-1", List.of("v1", "v2"), LocalDate.of(2026, 6, 30), null));

        assertThat(started.jobId()).isEqualTo("job-2");
        server.verify();
    }

    @Test
    void 예측_시작은_예측_경로로_보낸다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/prediction"))
                .andExpect(content().json("{\"analysisRunId\":\"run-1\",\"asOfDate\":\"2026-06-30\"}", JsonCompareMode.STRICT))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"jobId\":\"job-3\"}"));

        assertThat(client.startPrediction(new PredictionRequest("run-1", LocalDate.of(2026, 6, 30))).jobId())
                .isEqualTo("job-3");
    }

    @Test
    void 미등록_작업의_501은_시작_실패로_알린다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/analysis"))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"아직 지원하지 않는 작업입니다.\"}"));

        assertThatThrownBy(() -> client.startAnalysis(
                new AnalysisRequest("run-1", List.of("v1"), LocalDate.of(2026, 6, 30), null)))
                .isInstanceOf(AiCallRejectedException.class)
                .hasMessage("ai 서비스가 아직 지원하지 않는 작업입니다.");
    }

    @Test
    void 시작_호출의_그_밖의_오류는_본문을_노출하지_않고_시작_실패로_알린다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/prediction"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":[{\"loc\":[\"body\",\"asOfDate\"]}]}"));

        assertThatThrownBy(() -> client.startPrediction(new PredictionRequest("run-1", LocalDate.of(2026, 6, 30))))
                .isInstanceOf(AiCallRejectedException.class)
                .hasMessage("ai 서비스가 작업 시작을 거절했습니다.");
    }

    @Test
    void 시작_응답에_jobId가_없으면_실패로_본다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/prediction"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThatThrownBy(() -> client.startPrediction(new PredictionRequest("run-1", LocalDate.of(2026, 6, 30))))
                .isInstanceOf(AiCallFailedException.class)
                .isNotInstanceOf(AiCallRejectedException.class);  // 접수됐는지 알 수 없는 실패
    }

    @Test
    void 상태_조회는_상태와_결과를_변환한다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/job-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", KEY))
                .andRespond(withSuccess("""
                        {"jobId":"job-1","status":"SUCCEEDED","result":{"summary":"요약"},"errorMessage":null}""",
                        MediaType.APPLICATION_JSON));

        JobStatusResponse status = client.getJobStatus(com.telescope.analysis.job.entity.JobType.ANALYSIS, "job-1");

        assertThat(status.status()).isEqualTo(AiJobStatus.SUCCEEDED);
        assertThat(status.result()).isEqualTo(Map.of("summary", "요약"));
        assertThat(status.errorMessage()).isNull();
    }

    @Test
    void 실패한_작업은_사유를_그대로_돌려준다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/job-1"))
                .andRespond(withSuccess("""
                        {"jobId":"job-1","status":"FAILED","result":null,"errorMessage":"원본 파일을 찾을 수 없습니다."}""",
                        MediaType.APPLICATION_JSON));

        JobStatusResponse status = client.getJobStatus(com.telescope.analysis.job.entity.JobType.ANALYSIS, "job-1");

        assertThat(status.status()).isEqualTo(AiJobStatus.FAILED);
        assertThat(status.errorMessage()).isEqualTo("원본 파일을 찾을 수 없습니다.");
    }

    @Test
    void 상태_조회의_404만_없는_작업으로_본다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/missing")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.getJobStatus(com.telescope.analysis.job.entity.JobType.ANALYSIS, "missing")).isInstanceOf(AiJobNotFoundException.class);
    }

    @Test
    void 상태_조회의_5xx는_없는_작업이_아니라_일시_오류로_본다() {
        server.expect(requestTo(BASE + "/internal/v1/jobs/job-1")).andRespond(withServerError());

        assertThatThrownBy(() -> client.getJobStatus(com.telescope.analysis.job.entity.JobType.ANALYSIS, "job-1"))
                .isInstanceOf(AiCallFailedException.class)
                .isNotInstanceOf(AiJobNotFoundException.class);
    }
}
