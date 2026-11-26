package com.telescope.backend.job.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 오래 걸리는 작업(자료 준비, 분석, 예측)의 상태.
 * 대상(DocumentVersion 등)이 {@code jobId}로 이 Job을 가리키며, Job은 대상을 모른다.
 *
 * MVP: 소유자 컬럼이 없고 낙관적 락({@code @Version})도 없다. 같은 작업의 동시 갱신은
 * 서비스가 행 잠금({@code JobRepository#findByIdForUpdate})으로 직렬화해 막는다.
 */
@Getter
@Entity
@Table(name = "job")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = 100)
    private String externalJobId;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** 결과 저장에 실패한 횟수. 한도에 닿으면 작업을 FAILED로 끝낸다(#63). */
    @Column(nullable = false)
    private int resultSaveFailures;

    private Job(JobType jobType) {
        this.jobType = jobType;
        this.status = JobStatus.QUEUED;
    }

    public static Job create(JobType jobType) {
        return new Job(jobType);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public void assignExternalJobId(String externalJobId) {
        this.externalJobId = externalJobId;
    }

    public void start(LocalDateTime now) {
        transitionTo(JobStatus.RUNNING);
        this.startedAt = now;
    }

    public void succeed(LocalDateTime now) {
        transitionTo(JobStatus.SUCCEEDED);
        this.finishedAt = now;
    }

    public void fail(String errorMessage, LocalDateTime now) {
        transitionTo(JobStatus.FAILED);
        this.errorMessage = errorMessage;
        this.finishedAt = now;
    }

    /**
     * ai 서비스가 알려 준 상태를 반영한다. 폴링 때마다 호출되므로 예외 대신 무시하는 경우가 있다.
     *  이미 종료됐거나 같은 상태면 아무것도 하지 않는다.
     *  ai가 QUEUED라고 해도 되돌리지 않는다.
     *  RUNNING을 건너뛰고 바로 SUCCEEDED를 받으면 시작 처리 후 완료한다.
     */
    public void syncFrom(JobStatus aiStatus, String aiErrorMessage, LocalDateTime now) {
        if (isTerminal() || status == aiStatus) {
            return;
        }
        switch (aiStatus) {
            case QUEUED -> { }
            case RUNNING -> start(now);
            case SUCCEEDED -> {
                if (status == JobStatus.QUEUED) {
                    start(now);
                }
                succeed(now);
            }
            case FAILED -> fail(aiErrorMessage, now);
        }
    }

    /**
     * 결과 저장 실패를 한 번 기록한다. 한도({@code maxAttempts})에 닿으면 사유와 함께 FAILED로 끝낸다.
     * 이미 끝난 작업이면 아무것도 하지 않는다(다른 요청이 먼저 끝냈을 수 있다).
     *
     * @return 이번 기록으로 작업을 포기했으면 true
     */
    public boolean recordResultSaveFailure(int maxAttempts, String errorMessage, LocalDateTime now) {
        if (isTerminal()) {
            return false;
        }
        resultSaveFailures++;
        if (resultSaveFailures < maxAttempts) {
            return false;
        }
        fail(errorMessage, now);
        return true;
    }

    private void transitionTo(JobStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.JOB_INVALID_TRANSITION,
                    "작업 상태를 " + status + "에서 " + next + "(으)로 바꿀 수 없습니다.");
        }
        this.status = next;
    }
}
