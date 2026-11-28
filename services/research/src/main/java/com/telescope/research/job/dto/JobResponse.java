package com.telescope.research.job.dto;

import com.telescope.research.job.entity.Job;
import com.telescope.research.job.entity.JobStatus;
import com.telescope.research.job.entity.JobType;

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
