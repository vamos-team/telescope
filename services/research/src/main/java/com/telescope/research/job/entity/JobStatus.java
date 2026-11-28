package com.telescope.research.job.entity;

import java.util.Set;

/**
 * 작업 상태. 허용 전이는 {@code QUEUED → RUNNING → SUCCEEDED/FAILED}이고,
 * ai 호출 실패로 시작하지 못한 작업을 위해 {@code QUEUED → FAILED}도 허용한다.
 * SUCCEEDED, FAILED는 종료 상태라 더 바뀌지 않는다.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean canTransitionTo(JobStatus next) {
        return allowedNext().contains(next);
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }

    private Set<JobStatus> allowedNext() {
        return switch (this) {
            case QUEUED -> Set.of(RUNNING, FAILED);
            case RUNNING -> Set.of(SUCCEEDED, FAILED);
            case SUCCEEDED, FAILED -> Set.of();
        };
    }
}
