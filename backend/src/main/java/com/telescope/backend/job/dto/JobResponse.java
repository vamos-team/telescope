package com.telescope.backend.job.dto;

import com.telescope.backend.job.entity.Job;
import com.telescope.backend.job.entity.JobStatus;
import com.telescope.backend.job.entity.JobType;

import java.time.LocalDateTime;

/** 작업 상태 응답. 작업 결과 본문은 담지 않는다. */
public record JobResponse(
        Long id,
        JobType jobType,
        JobStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String errorMessage
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getJobType(),
                job.getStatus(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getErrorMessage()
        );
    }
}
