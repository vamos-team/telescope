package com.telescope.backend.infra.python;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * ai 서비스에 예상 질문 생성을 요청하는 계약.
 *
 * <p>TODO(가칭): 예상 질문은 분석 결과를 입력으로 쓸 가능성이 높은데,
 * {@code analysis} 패키지가 아직 없어 분석 결과 자체를 넘기지 못하고
 * {@code analysisRunId}만 참조로 넘긴다. ai 서비스가 분석 결과를 어떻게 다시 조회할지
 * (MCP 조회 도구 경유 등)는 아직 정해지지 않았다.
 *
 * @param analysisRunId 예상 질문의 근거가 되는 분석 실행 식별자
 * @param asOfDate 기준 시점. 분석과 동일한 제약을 따른다
 */
public record PredictionRequest(
        @NotBlank String analysisRunId,
        @NotNull LocalDate asOfDate
) {
}
