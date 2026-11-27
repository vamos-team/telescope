package com.telescope.analysis.infra.python;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.util.StringUtils;

/**
 * ai/server를 HTTP로 호출하는 {@link PythonAiClient} 구현({@code infra.python-ai-client.mode=http}).
 *
 * <p>경로·응답은 {@code docs/backend/ai-contract.md} 2.4, 오류 구분은 2.5, 인증·타임아웃은 2.10을 따른다.
 * <ul>
 *   <li>시작 호출: {@code POST /internal/v1/jobs/{종류}} → {@code 202 {"jobId"}}. 실패(타임아웃·연결 실패·
 *       {@code 4xx}/{@code 5xx}, 미등록 작업의 {@code 501} 포함)는 {@link AiCallFailedException}.
 *       호출한 쪽이 작업을 {@code FAILED}로 끝내고 자동 재시도하지 않는다.</li>
 *   <li>상태 조회: {@code GET /internal/v1/jobs/{jobId}}. {@code 404}만 {@link AiJobNotFoundException}이고,
 *       그 밖의 실패는 {@link AiCallFailedException}(일시 오류, 다음 폴링에서 재시도).</li>
 * </ul>
 *
 * <p>로그와 예외 메시지에 요청·응답 본문과 비밀키를 남기지 않는다. 빈 등록은 {@link PythonAiClientConfig}가 한다.
 */
@Slf4j
public class PythonAiClientHttp implements PythonAiClient {

    static final String JOBS_PATH = "/internal/v1/jobs";

    private final RestClient startClient;
    private final RestClient statusClient;

    /**
     * 시작 호출용과 상태 조회용 클라이언트를 받는다. 둘은 읽기 타임아웃만 다르고,
     * 주소와 {@code X-Internal-Api-Key} 헤더는 이미 설정돼 있어야 한다.
     */
    public PythonAiClientHttp(RestClient startClient, RestClient statusClient) {
        this.startClient = startClient;
        this.statusClient = statusClient;
    }

    /** 자료 준비 작업을 시작한다. */
    @Override
    public JobStartResponse startDocumentPreparation(DocumentPreparationRequest request) {
        return start("document-preparation", request);
    }

    /** 분석 작업을 시작한다. */
    @Override
    public JobStartResponse startAnalysis(AnalysisRequest request) {
        return start("analysis", request);
    }

    /** 예상 질문 작업을 시작한다. */
    @Override
    public JobStartResponse startPrediction(PredictionRequest request) {
        return start("prediction", request);
    }

    /** 재무 데이터 적재 작업을 시작한다(#135). */
    @Override
    public JobStartResponse startDataSync(DataSyncRequest request) {
        return start("data-sync", request);
    }

    /**
     * 작업 상태를 조회한다.
     *
     * @throws AiJobNotFoundException ai/server가 {@code 404}(모르는 jobId)로 답했을 때
     * @throws AiCallFailedException  그 밖의 실패. 일시 오류로 본다
     */
    @Override
    public JobStatusResponse getJobStatus(String jobId) {
        JobStatusResponse response;
        try {
            response = statusClient.get()
                    .uri(JOBS_PATH + "/{jobId}", jobId)
                    .retrieve()
                    .body(JobStatusResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new AiJobNotFoundException(jobId);
        } catch (RestClientException e) {
            log.warn("ai 작업 상태 조회 실패: jobId={}, reason={}", jobId, describe(e));
            throw new AiCallFailedException("ai 서비스의 작업 상태를 확인하지 못했습니다.", e);
        }
        if (response == null || response.status() == null) {
            throw new AiCallFailedException("ai 서비스의 작업 상태 응답 형식이 올바르지 않습니다.");
        }
        return response;
    }

    /** 작업 종류별 시작 경로로 요청을 보내고 발급받은 jobId를 돌려준다. */
    private JobStartResponse start(String jobKind, Object request) {
        JobStartResponse response;
        try {
            response = startClient.post()
                    .uri(JOBS_PATH + "/" + jobKind)
                    .body(request)
                    .retrieve()
                    .body(JobStartResponse.class);
        } catch (RestClientResponseException e) {
            log.warn("ai 작업 시작 거절: kind={}, status={}", jobKind, e.getStatusCode().value());
            throw new AiCallRejectedException(startRejectedMessage(e), e);
        } catch (RestClientException e) {
            log.warn("ai 작업 시작 실패: kind={}, reason={}", jobKind, describe(e));
            throw new AiCallFailedException("ai 서비스에 작업을 시작하지 못했습니다.", e);
        }
        if (response == null || !StringUtils.hasText(response.jobId())) {
            throw new AiCallFailedException("ai 서비스의 작업 시작 응답 형식이 올바르지 않습니다.");
        }
        return response;
    }

    /** 시작 거절의 사용자용 문구. 미등록 작업({@code 501}, #86)만 따로 알린다. */
    private static String startRejectedMessage(RestClientResponseException e) {
        if (e.getStatusCode().value() == HttpStatus.NOT_IMPLEMENTED.value()) {
            return "ai 서비스가 아직 지원하지 않는 작업입니다.";
        }
        return "ai 서비스가 작업 시작을 거절했습니다.";
    }

    /** 로그용 실패 요약. 응답 본문이 섞이지 않도록 예외 종류와 상태 코드만 쓴다. */
    private static String describe(RestClientException e) {
        if (e instanceof RestClientResponseException r) {
            return "status=" + r.getStatusCode().value();
        }
        return e.getClass().getSimpleName();
    }
}
