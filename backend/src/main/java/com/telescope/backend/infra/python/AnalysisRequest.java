package com.telescope.backend.infra.python;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * ai 서비스에 분석(근거 점검) 실행을 요청하는 계약.
 *
 * <p>TODO(가칭): {@code analysis}, {@code profile} 패키지가 아직 없어서
 * {@code analysisRunId}, {@code profileSnapshotJson}은 자리만 잡아둔 형태다.
 * profile 패키지가 생기면 {@code profileSnapshotJson}(문자열)을 실제 스냅샷 타입으로 바꾼다.
 *
 * <p>{@code allowedDocumentVersionIds}, {@code asOfDate}는 CLAUDE.md의
 * "허용 문서 범위와 기준 시점은 서버가 강제한다" 원칙에 따라 서버(backend)가 결정해서
 * 넘기는 값이다. ai 서비스가 임의로 넓히거나 무시할 수 없다.
 *
 * @param analysisRunId 분석 실행 식별자
 * @param allowedDocumentVersionIds 이 분석에서 근거로 허용되는 문서 버전 식별자 목록 (서버가 확정한 스냅샷)
 * @param asOfDate 기준 시점. 이 시점 이후 발행된 자료는 근거로 쓸 수 없다
 * @param profileSnapshotJson 분석 실행 시점의 프로필(추출 항목, 검토 기준 등) 스냅샷. profile 패키지 확정 전까지 JSON 문자열로 전달
 */
public record AnalysisRequest(
        @NotBlank String analysisRunId,
        @NotEmpty List<String> allowedDocumentVersionIds,
        @NotNull LocalDate asOfDate,
        String profileSnapshotJson
) {
}
