package com.telescope.backend.datasync.dto;

import com.telescope.backend.job.entity.JobStatus;

import java.time.LocalDateTime;

/**
 * 적재 실행 하나(관리자 화면).
 *
 * @param id            실행 번호
 * @param source        원천(지금은 {@code dart_xbrl})
 * @param requestedBy   직접 실행한 관리자 ID. 스케줄러가 시작했으면 비어 있다
 * @param createdAt     실행을 만든 시각
 * @param jobId         작업 ID. 진행 상황은 {@code GET /jobs/{jobId}}로도 볼 수 있다
 * @param status        작업 상태
 * @param startedAt     시작 시각
 * @param finishedAt    끝난 시각
 * @param errorMessage  실패 사유
 * @param dataVersionId 이 실행이 만든 판. 성공했을 때만 있다
 * @param active        그 판이 지금 활성인지
 */
public record DataSyncRunResponse(
        Long id,
        String source,
        Long requestedBy,
        LocalDateTime createdAt,
        Long jobId,
        JobStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMessage,
        Long dataVersionId,
        boolean active
) {
}
