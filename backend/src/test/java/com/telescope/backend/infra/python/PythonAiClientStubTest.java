package com.telescope.backend.infra.python;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PythonAiClientStub}의 상태 전이(QUEUED/RUNNING → SUCCEEDED) 흐름을 확인한다.
 *
 * <p>RUNNING 유지 시간이 하드코딩돼 있어(이슈 #10 시점 기준 3초) 이 테스트는 그만큼 실제로
 * 대기한다. 이 상수가 설정값으로 빠지면 테스트에서 주입해서 빠르게 돌리는 걸 검토한다.
 */
class PythonAiClientStubTest {

    private final PythonAiClientStub client = new PythonAiClientStub();

    @Test
    void 작업을_시작하면_바로_조회했을_때_RUNNING이고_충분히_기다리면_SUCCEEDED로_바뀐다() throws InterruptedException {
        JobStartResponse started = client.startPrediction(
                new PredictionRequest("run-1", LocalDate.now())
        );
        assertThat(started.jobId()).isNotBlank();

        JobStatusResponse immediately = client.getJobStatus(started.jobId());
        assertThat(immediately.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(immediately.result()).isNull();

        Thread.sleep(3_200);

        JobStatusResponse afterWait = client.getJobStatus(started.jobId());
        assertThat(afterWait.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(afterWait.result()).isNotNull();
        assertThat(afterWait.errorMessage()).isNull();
    }

    @Test
    void 모르는_jobId를_조회하면_예외를_던진다() {
        assertThatThrownBy(() -> client.getJobStatus("no-such-job"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
