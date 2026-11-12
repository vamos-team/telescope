package com.telescope.backend.infra.python;

/**
 * ai(Python) 서비스 호출 인터페이스.
 *
 * <p>기능 패키지(document, job, analysis, prediction 등)는 이 인터페이스에만 의존하고,
 * 구현체({@link PythonAiClientStub} 또는 이후 추가될 실제 HTTP 구현체)는 설정으로 교체한다.
 * CLAUDE.md의 "외부 시스템은 infra 인터페이스로만 접근한다" 규칙에 따른다.
 *
 * <p>TODO(가칭): 메서드 시그니처와 DTO 필드는 ai 담당자와 아직 조율하지 않은 초안이다.
 * 실제 ai/ FastAPI 엔드포인트 계약이 나오면 다시 맞춘다.
 */
public interface PythonAiClient {

    /**
     * 자료 준비(ingest) 작업을 시작한다.
     */
    JobStartResponse startDocumentPreparation(DocumentPreparationRequest request);

    /**
     * 분석(근거 점검) 작업을 시작한다.
     */
    JobStartResponse startAnalysis(AnalysisRequest request);

    /**
     * 예상 질문 생성 작업을 시작한다.
     */
    JobStartResponse startPrediction(PredictionRequest request);

    /**
     * 작업 상태를 조회한다. 상태 폴링 용도로 반복 호출된다.
     *
     * @param jobId {@link JobStartResponse#jobId()}로 발급받은 식별자
     * @throws IllegalArgumentException jobId에 해당하는 작업이 없으면 던진다 (TODO: 전용 예외로 교체 검토, global 패키지의 BusinessException/ErrorCode 확정 후)
     */
    JobStatusResponse getJobStatus(String jobId);
}
