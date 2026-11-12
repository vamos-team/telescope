package com.telescope.backend.infra.python;

/**
 * ai 서비스에 작업(자료 준비/분석/예상 질문)을 시작시킨 직후 받는 응답.
 *
 * @param jobId 발급된 작업 식별자. 이후 {@link PythonAiClient#getJobStatus}로 폴링한다
 */
public record JobStartResponse(String jobId) {
}
