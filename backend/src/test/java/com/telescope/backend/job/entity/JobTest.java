package com.telescope.backend.job.entity;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 9, 0);

    @Test
    void 새_작업은_QUEUED이고_시각이_비어_있다() {
        Job job = Job.create(JobType.ANALYSIS);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
    }

    @Test
    void 정상_흐름은_QUEUED_RUNNING_SUCCEEDED이고_시각이_기록된다() {
        Job job = Job.create(JobType.ANALYSIS);

        job.start(NOW);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(NOW);

        job.succeed(NOW.plusMinutes(1));
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getFinishedAt()).isEqualTo(NOW.plusMinutes(1));
    }

    @Test
    void 실행_중_실패하면_사유와_종료_시각이_기록된다() {
        Job job = Job.create(JobType.PREDICTION);
        job.start(NOW);

        job.fail("ai 오류", NOW.plusSeconds(5));

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("ai 오류");
        assertThat(job.getFinishedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void 시작하지_못한_작업은_QUEUED에서_바로_FAILED가_될_수_있다() {
        Job job = Job.create(JobType.DOCUMENT_PREPARATION);

        job.fail("시작 요청 실패", NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getStartedAt()).isNull();
    }

    @Test
    void QUEUED에서_바로_SUCCEEDED로는_갈_수_없다() {
        Job job = Job.create(JobType.ANALYSIS);

        assertThatThrownBy(() -> job.succeed(NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.JOB_INVALID_TRANSITION));
    }

    @Test
    void 종료된_작업은_다시_바꿀_수_없다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);
        job.succeed(NOW);

        assertThatThrownBy(() -> job.start(NOW)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> job.fail("x", NOW)).isInstanceOf(BusinessException.class);
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void RUNNING에서_다시_start하면_거부된다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);

        assertThatThrownBy(() -> job.start(NOW)).isInstanceOf(BusinessException.class);
    }

    @Test
    void ai가_같은_상태를_알려_주면_아무것도_바뀌지_않는다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);

        job.syncFrom(JobStatus.RUNNING, null, NOW.plusMinutes(1));

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getStartedAt()).isEqualTo(NOW);
    }

    @Test
    void ai가_RUNNING을_건너뛰고_SUCCEEDED를_알려_주면_시작_처리_후_완료한다() {
        Job job = Job.create(JobType.ANALYSIS);

        job.syncFrom(JobStatus.SUCCEEDED, null, NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getStartedAt()).isEqualTo(NOW);
        assertThat(job.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    void ai가_QUEUED를_알려_줘도_진행_중인_작업을_되돌리지_않는다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);

        job.syncFrom(JobStatus.QUEUED, null, NOW);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void 종료된_작업은_ai_상태를_받아도_바뀌지_않는다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);
        job.succeed(NOW);

        job.syncFrom(JobStatus.FAILED, "늦게 온 실패", NOW.plusMinutes(1));

        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    void 결과_저장_실패는_한도_전까지_세기만_하고_한도에_닿으면_FAILED가_된다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);

        assertThat(job.recordResultSaveFailure(2, "저장 실패", NOW)).isFalse();
        assertThat(job.getResultSaveFailures()).isEqualTo(1);
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);

        assertThat(job.recordResultSaveFailure(2, "저장 실패", NOW.plusSeconds(1))).isTrue();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("저장 실패");
        assertThat(job.getFinishedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void 이미_끝난_작업의_결과_저장_실패는_기록하지_않는다() {
        Job job = Job.create(JobType.ANALYSIS);
        job.start(NOW);
        job.succeed(NOW);

        assertThat(job.recordResultSaveFailure(1, "저장 실패", NOW)).isFalse();
        assertThat(job.getResultSaveFailures()).isZero();
        assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }
}
