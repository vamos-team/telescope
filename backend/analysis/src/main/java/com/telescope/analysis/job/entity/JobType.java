package com.telescope.analysis.job.entity;

public enum JobType {
    DOCUMENT_PREPARATION,
    ANALYSIS,
    PREDICTION,
    /** 재무 데이터 적재(#135). 대상은 {@code DataSyncRun}이 {@code jobId}로 가리킨다 */
    DATA_SYNC
}
