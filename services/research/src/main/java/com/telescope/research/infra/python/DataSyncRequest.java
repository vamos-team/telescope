package com.telescope.research.infra.python;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * 재무 데이터 적재 요청(#135). ai/server가 수집기·적재기를 돌려 새 DuckDB 파일을 만든다.
 *
 * @param dataSyncRunId 실행 번호. 새 DuckDB 파일 이름(finance-&lt;번호&gt;.duckdb)에 쓰인다
 * @param corpCodes     대상 회사 DART 고유번호
 * @param start         공시 접수 기간 시작
 * @param end           공시 접수 기간 끝
 */
public record DataSyncRequest(
        @NotBlank String dataSyncRunId,
        @NotEmpty List<String> corpCodes,
        @NotNull LocalDate start,
        @NotNull LocalDate end
) {
}
