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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>작업이 끝나면 결과는 대상 도메인의 {@link JobResultHandler}가 저장한다. 결과 저장과 완료 확정은 한 트랜잭션이다(#63).
 */
@Slf4j
@Service
public class JobService {

    /** 결과 저장이 한도만큼 실패해 포기할 때 남기는 사유. 사용자에게 보인다. */
    static final String RESULT_SAVE_FAILED_MESSAGE = "작업 결과를 저장하지 못했습니다.";

    private final JobRepository jobRepository;
    private final PythonAiClient aiClient;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<JobType, JobResultHandler> resultHandlers;
    private final int resultSaveMaxAttempts;

    /**
     * 의존성과 작업 종류별 결과 처리기를 받는다.
     *
     * @param resultHandlers        대상 도메인이 등록한 처리기. 아직 없는 작업 종류도 있다
     * @param resultSaveMaxAttempts 결과 저장 실패를 몇 번까지 다시 시도할지({@code app.job.result-save-max-attempts})
     * @throws IllegalStateException 같은 작업 종류의 처리기가 둘 이상이거나 한도가 1보다 작을 때
     */
    public JobService(JobRepository jobRepository, PythonAiClient aiClient, TransactionTemplate tx, Clock clock,
                      List<JobResultHandler> resultHandlers,
                      @Value("${app.job.result-save-max-attempts:5}") int resultSaveMaxAttempts) {
        if (resultSaveMaxAttempts < 1) {
            throw new IllegalStateException("app.job.result-save-max-attempts는 1 이상이어야 합니다.");
        }
        this.jobRepository = jobRepository;
        this.aiClient = aiClient;
        this.tx = tx;
        this.clock = clock;
        this.resultHandlers = indexByJobType(resultHandlers);
        this.resultSaveMaxAttempts = resultSaveMaxAttempts;
    }

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

    /** 작업이 끝났는지(SUCCEEDED·FAILED). ai를 부르지 않고 저장된 상태만 본다. 없는 작업이면 404용 예외. */
    @Transactional(readOnly = true)
    public boolean isFinished(Long jobId) {
        return findOrThrow(jobId).isTerminal();
    }

    /** 이 종류의 끝나지 않은 작업 ID. 화면이 폴링하지 않는 배치(데이터 적재 등)를 서버가 직접 확인할 때 쓴다. */
    @Transactional(readOnly = true)
    public List<Long> unfinishedJobIds(JobType jobType) {
        return jobRepository.findIdsByJobTypeAndStatusIn(jobType, List.of(JobStatus.QUEUED, JobStatus.RUNNING));
    }

    /**
     * 이 종류의 작업 중 ai 접수 번호를 아직 저장하지 못한 것(QUEUED). 시작 직후 서버가 멈췄거나, 시작 응답을 받지 못했거나,
     * 받은 번호를 저장하지 못한 경우다. 같은 요청을 다시 보내도 되는 작업(데이터 적재)만 이 목록으로 다시 보낸다.
     */
    @Transactional(readOnly = true)
    public List<Long> unsentJobIds(JobType jobType) {
        return jobRepository.findIdsByJobTypeAndStatusAndExternalJobIdIsNull(jobType, JobStatus.QUEUED);
    }

    /** 여러 작업의 저장된 상태를 ID별로 돌려준다(목록 화면용). ai를 부르지 않는다. */
    @Transactional(readOnly = true)
    public Map<Long, JobResponse> peek(Collection<Long> jobIds) {
        Map<Long, JobResponse> byId = new LinkedHashMap<>();
        for (Job job : jobRepository.findByIdIn(jobIds)) {
            byId.put(job.getId(), JobResponse.from(job));
        }
        return byId;
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
        if (ai.status() == AiJobStatus.SUCCEEDED) {
            return completeWithResult(jobId, ai.result());
        }
        return applyAiStatus(jobId, toJobStatus(ai.status()), ai.errorMessage());
    }

    /**
     * ai가 끝났다고 알려 준 작업의 결과를 저장하고 완료로 확정한다.
     *
     * <p>작업 행을 잠근 한 트랜잭션에서 처리기 저장과 완료 확정을 함께 한다. 잠근 뒤 다시 읽었을 때 이미 끝났으면
     * (다른 요청이 먼저 처리) 처리기를 부르지 않아 결과가 두 번 저장되지 않는다. 저장이 실패하면 둘 다 되돌리고,
     * 실패 횟수만 따로 기록해 다음 조회에서 다시 시도한다. 한도에 닿으면 FAILED로 끝낸다.
     * 처리기가 없는 작업 종류는 경고만 남기고 완료한다(대상 도메인이 처리기를 붙이기 전까지의 동작).
     */
    private JobResponse completeWithResult(Long jobId, Object result) {
        try {
            return inTx(() -> {
                Job job = lockOrThrow(jobId);
                if (job.isTerminal()) {
                    return JobResponse.from(job);
                }
                JobResultHandler handler = resultHandlers.get(job.getJobType());
                if (handler == null) {
                    // TODO(#63 후속): 대상 도메인(#111 등)이 처리기를 붙이면 결과가 저장된다. 그 전까지 결과는 버려진다
                    log.warn("결과 처리기가 없어 결과를 저장하지 않고 완료한다: jobId={}, jobType={}", jobId, job.getJobType());
                } else {
                    handler.saveResult(jobId, result);
                }
                job.syncFrom(JobStatus.SUCCEEDED, null, now());
                return JobResponse.from(job);
            });
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // 결과 본문이 섞일 수 있어 예외 종류만 남긴다
            log.warn("작업 결과 저장 실패, 다음 조회에서 다시 시도한다: jobId={}, reason={}", jobId, e.getClass().getSimpleName());
            return recordResultSaveFailure(jobId);
        }
    }

    /** 결과 저장 실패를 새 트랜잭션에서 기록한다(저장 트랜잭션은 이미 되돌려졌다). 한도에 닿으면 FAILED가 된다. */
    private JobResponse recordResultSaveFailure(Long jobId) {
        return inTx(() -> {
            Job job = lockOrThrow(jobId);
            if (job.recordResultSaveFailure(resultSaveMaxAttempts, RESULT_SAVE_FAILED_MESSAGE, now())) {
                log.error("결과 저장이 {}번 실패해 작업을 FAILED로 끝냈다: jobId={}", resultSaveMaxAttempts, jobId);
            }
            return JobResponse.from(job);
        });
    }

    /** ai가 알려 준 진행 중·실패 상태를 행을 잠근 뒤 반영한다. 완료는 {@link #completeWithResult}가 맡는다. */
    private JobResponse applyAiStatus(Long jobId, JobStatus aiStatus, String errorMessage) {
        return inTx(() -> {
            Job job = lockOrThrow(jobId);
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

    /** 작업 행을 잠가 다시 읽는다. 같은 작업의 동시 갱신을 한 줄로 세운다. */
    private Job lockOrThrow(Long jobId) {
        return jobRepository.findByIdForUpdate(jobId).orElseThrow(() -> notFound(jobId));
    }

    /** 처리기를 작업 종류로 찾을 수 있게 모은다. 같은 종류가 둘이면 어느 쪽이 저장할지 모호하므로 기동을 멈춘다. */
    private static Map<JobType, JobResultHandler> indexByJobType(List<JobResultHandler> handlers) {
        Map<JobType, JobResultHandler> byType = new EnumMap<>(JobType.class);
        for (JobResultHandler handler : handlers) {
            JobResultHandler previous = byType.putIfAbsent(handler.jobType(), handler);
            if (previous != null) {
                throw new IllegalStateException("작업 종류 " + handler.jobType() + "의 결과 처리기가 둘 이상입니다.");
            }
        }
        return byType;
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
