package com.telescope.backend.job.service;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.infra.python.AiJobNotFoundException;
import com.telescope.backend.infra.python.AiJobStatus;
import com.telescope.backend.infra.python.JobStatusResponse;
import com.telescope.backend.infra.python.PythonAiClient;
import com.telescope.backend.job.dto.JobResponse;
import com.telescope.backend.job.entity.Job;
import com.telescope.backend.job.entity.JobStatus;
import com.telescope.backend.job.entity.JobType;
import com.telescope.backend.job.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 작업 상태 관리.
 *
 * <p>다른 도메인(document, analysis, prediction)은 작업을 시작할 때 이 순서로 쓴다.
 * <ol>
 *   <li>대상 저장과 같은 트랜잭션에서 {@link #create} (QUEUED)</li>
 *   <li>트랜잭션 밖에서 ai 서비스 시작 요청</li>
 *   <li>성공하면 {@link #markStarted}, 실패하면 {@link #markFailed}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final PythonAiClient aiClient;
    private final TransactionTemplate tx;
    private final Clock clock;

    @Transactional
    public Job create(JobType jobType) {
        return jobRepository.save(Job.create(jobType));
    }

    @Transactional
    public void markStarted(Long jobId, String externalJobId) {
        Job job = findOrThrow(jobId);
        job.assignExternalJobId(externalJobId);
        job.start(now());
    }

    @Transactional
    public void markFailed(Long jobId, String errorMessage) {
        findOrThrow(jobId).fail(errorMessage, now());
    }

    /**
     * 작업 상태를 조회한다. 아직 끝나지 않은 작업이면 ai 서비스의 최신 상태를 받아 저장한 뒤 돌려준다.
     *
     * <p>MVP: 조회할 때 갱신한다. 별도 갱신 배치(스케줄러)는 트리거 주체가 정해지지 않아 두지 않았다.
     * 그래서 아무도 조회하지 않는 작업은 갱신되지 않고, 사용자가 늘면 ai 호출도 같이 늘어난다.
     * ai 호출은 트랜잭션 밖에서 한다. 저장할 때는 행을 잠그고 다시 읽으므로, 그 사이 다른 요청이
     * 먼저 종료시켰다면 그 상태가 유지된다({@link Job#syncFrom}).
     */
    public JobResponse getJob(Long jobId) {
        Job job = inTx(() -> findOrThrow(jobId));
        if (job.isTerminal() || job.getExternalJobId() == null) {
            return JobResponse.from(job);
        }

        JobStatusResponse ai;
        try {
            ai = aiClient.getJobStatus(job.getExternalJobId());
        } catch (AiJobNotFoundException e) {
            // ai 쪽에서 작업이 사라졌다(예: Stub은 재시작하면 상태를 잃는다). 더 기다려도 끝나지 않는다.
            return applyAiStatus(jobId, JobStatus.FAILED, "ai 서비스에서 작업을 찾을 수 없습니다.");
        } catch (RuntimeException e) {
            // 일시적 오류일 수 있으므로 상태는 그대로 두고, 다음 폴링에서 다시 시도한다.
            log.warn("ai 작업 상태 조회 실패: jobId={}, reason={}", jobId, e.getMessage());
            return JobResponse.from(job);
        }
        return applyAiStatus(jobId, toJobStatus(ai.status()), ai.errorMessage());
    }

    private JobResponse applyAiStatus(Long jobId, JobStatus aiStatus, String errorMessage) {
        return inTx(() -> {
            Job job = jobRepository.findByIdForUpdate(jobId).orElseThrow(() -> notFound(jobId));
            job.syncFrom(aiStatus, errorMessage, now());
            return JobResponse.from(job);
        });
    }

    private JobStatus toJobStatus(AiJobStatus aiStatus) {
        return switch (aiStatus) {
            case QUEUED -> JobStatus.QUEUED;
            case RUNNING -> JobStatus.RUNNING;
            case SUCCEEDED -> JobStatus.SUCCEEDED;
            case FAILED -> JobStatus.FAILED;
        };
    }

    private Job findOrThrow(Long jobId) {
        return jobRepository.findById(jobId).orElseThrow(() -> notFound(jobId));
    }

    private BusinessException notFound(Long jobId) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ID " + jobId + "번 작업을 찾을 수 없습니다.");
    }

    private <T> T inTx(Supplier<T> action) {
        return Objects.requireNonNull(tx.execute(status -> action.get()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
