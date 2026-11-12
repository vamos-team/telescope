package com.telescope.backend.infra.python;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link PythonAiClient}의 가짜(Stub) 구현.
 *
 * <p>ai(Python) 서비스가 아직 없어도 backend 개발을 진행할 수 있도록, 작업을 실제로
 * 수행하지 않고 메모리에만 기록한다. 상태는 시작 시각 기준 경과 시간으로 RUNNING → SUCCEEDED로
 * 단계적으로 바뀐다.
 *
 * <p>설정 {@code infra.python-ai-client.mode}가 {@code stub}이거나 비어있으면(기본값) 이 구현이
 * 등록된다. 실제 HTTP 구현체가 생기면 같은 프로퍼티로 교체한다.
 *
 * <p>TODO: 지금은 프로세스 메모리에만 상태를 둬서 재시작하면 모든 작업 상태가 사라진다.
 * Stub 용도로는 문제없지만, 실제 구현체는 당연히 이 방식을 쓰면 안 된다.
 *
 * <p>TODO: RUNNING 유지 시간({@link #RUNNING_DURATION})과 가짜 결과 내용이 하드코딩돼 있다.
 * 테스트에서 상태 전이를 확인하는 용도 이상으로 쓸 일이 생기면(데모 등) 설정값으로 뺀다.
 */
@Component
@ConditionalOnProperty(
        prefix = "infra.python-ai-client",
        name = "mode",
        havingValue = "stub",
        matchIfMissing = true
)
public class PythonAiClientStub implements PythonAiClient {

    private static final Logger log = LoggerFactory.getLogger(PythonAiClientStub.class);

    private static final Duration RUNNING_DURATION = Duration.ofSeconds(3);

    private final Map<String, Instant> startedAtByJobId = new ConcurrentHashMap<>();

    @Override
    public JobStartResponse startDocumentPreparation(DocumentPreparationRequest request) {
        return startJob("document-preparation", request.documentId());
    }

    @Override
    public JobStartResponse startAnalysis(AnalysisRequest request) {
        return startJob("analysis", request.analysisRunId());
    }

    @Override
    public JobStartResponse startPrediction(PredictionRequest request) {
        return startJob("prediction", request.analysisRunId());
    }

    @Override
    public JobStatusResponse getJobStatus(String jobId) {
        Instant startedAt = startedAtByJobId.get(jobId);
        if (startedAt == null) {
            throw new IllegalArgumentException("알 수 없는 jobId: " + jobId);
        }

        boolean stillRunning = Duration.between(startedAt, Instant.now()).compareTo(RUNNING_DURATION) < 0;
        if (stillRunning) {
            return new JobStatusResponse(jobId, JobStatus.RUNNING, null, null);
        }
        return new JobStatusResponse(jobId, JobStatus.SUCCEEDED, fakeResult(), null);
    }

    private JobStartResponse startJob(String jobKind, String reference) {
        String jobId = UUID.randomUUID().toString();
        startedAtByJobId.put(jobId, Instant.now());
        log.info("[stub] {} 작업 시작: jobId={}, reference={}", jobKind, jobId, reference);
        return new JobStartResponse(jobId);
    }

    private Object fakeResult() {
        // TODO(가칭): 실제 ai 응답 스키마가 정해지면 작업 종류별 결과 타입으로 교체한다.
        return Map.of("stub", true, "message", "PythonAiClientStub이 생성한 가짜 결과");
    }
}
