package com.telescope.backend.job.service;

import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.global.config.JpaAuditingConfig;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.infra.python.AiJobNotFoundException;
import com.telescope.backend.infra.python.AiJobStatus;
import com.telescope.backend.infra.python.AnalysisRequest;
import com.telescope.backend.infra.python.DocumentPreparationRequest;
import com.telescope.backend.infra.python.JobStartResponse;
import com.telescope.backend.infra.python.JobStatusResponse;
import com.telescope.backend.infra.python.PredictionRequest;
import com.telescope.backend.infra.python.PythonAiClient;
import com.telescope.backend.job.dto.JobResponse;
import com.telescope.backend.job.entity.Job;
import com.telescope.backend.job.entity.JobStatus;
import com.telescope.backend.job.entity.JobType;
import com.telescope.backend.job.repository.JobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서비스가 트랜잭션 경계를 직접 다루므로(ai 호출은 트랜잭션 밖) 테스트 전체를 감싸는
 * 트랜잭션을 끄고, 실제로 커밋되는 상태에서 검증한다. ai 서비스는 고정 응답을 주는 가짜로 대신한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class, JobService.class, JobServiceTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JobServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 9, 0);

    @Autowired
    JobRepository jobRepository;

    @Autowired
    FakeAiClient aiClient;

    @Autowired
    JobService jobService;

    @BeforeEach
    void setUp() {
        aiClient.reset();
    }

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
    }

    @Test
    void 만든_작업은_QUEUED이고_ai를_호출하지_않고_조회된다() {
        Job job = jobService.create(JobType.ANALYSIS);

        JobResponse response = jobService.getJob(job.getId());

        assertThat(response.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(aiClient.calls).isZero();
    }

    @Test
    void 시작하면_RUNNING이_되고_외부_작업_ID가_저장된다() {
        Job job = jobService.create(JobType.ANALYSIS);

        jobService.markStarted(job.getId(), "ext-1");

        Job saved = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(saved.getExternalJobId()).isEqualTo("ext-1");
        assertThat(saved.getStartedAt()).isEqualTo(NOW);
    }

    @Test
    void 시작하지_못하면_QUEUED에서_FAILED로_기록할_수_있다() {
        Job job = jobService.create(JobType.DOCUMENT_PREPARATION);

        jobService.markFailed(job.getId(), "ai 시작 요청 실패");

        JobResponse response = jobService.getJob(job.getId());
        assertThat(response.status()).isEqualTo(JobStatus.FAILED);
        assertThat(response.errorMessage()).isEqualTo("ai 시작 요청 실패");
    }

    @Test
    void ai가_SUCCEEDED를_알려_주면_조회_시_완료로_저장된다() {
        Long id = startedJob();
        aiClient.respond(AiJobStatus.SUCCEEDED, null);

        JobResponse response = jobService.getJob(id);

        assertThat(response.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(response.finishedAt()).isEqualTo(NOW);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void ai가_FAILED를_알려_주면_사유와_함께_저장된다() {
        Long id = startedJob();
        aiClient.respond(AiJobStatus.FAILED, "근거 부족");

        JobResponse response = jobService.getJob(id);

        assertThat(response.status()).isEqualTo(JobStatus.FAILED);
        assertThat(response.errorMessage()).isEqualTo("근거 부족");
    }

    @Test
    void ai가_RUNNING이면_상태는_그대로이고_매번_다시_물어본다() {
        Long id = startedJob();
        aiClient.respond(AiJobStatus.RUNNING, null);

        jobService.getJob(id);
        JobResponse response = jobService.getJob(id);

        assertThat(response.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(aiClient.calls).isEqualTo(2);
    }

    @Test
    void 종료된_작업은_ai를_다시_호출하지_않는다() {
        Long id = startedJob();
        aiClient.respond(AiJobStatus.SUCCEEDED, null);
        jobService.getJob(id);

        jobService.getJob(id);
        jobService.getJob(id);

        assertThat(aiClient.calls).isEqualTo(1);
    }

    @Test
    void ai에_없는_작업이면_FAILED로_기록한다() {
        Long id = startedJob();
        aiClient.error = new AiJobNotFoundException("ext-1");

        JobResponse response = jobService.getJob(id);

        assertThat(response.status()).isEqualTo(JobStatus.FAILED);
        assertThat(response.errorMessage()).isNotBlank();
    }

    @Test
    void ai_호출이_일시적으로_실패하면_상태를_바꾸지_않는다() {
        Long id = startedJob();
        aiClient.error = new IllegalStateException("connection refused");

        JobResponse response = jobService.getJob(id);

        assertThat(response.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void 없는_작업을_조회하면_404용_예외가_난다() {
        assertThatThrownBy(() -> jobService.getJob(999_999L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private Long startedJob() {
        Job job = jobService.create(JobType.ANALYSIS);
        jobService.markStarted(job.getId(), "ext-1");
        return job.getId();
    }

    /** {@code @Transactional}이 적용되도록 서비스를 직접 만들지 않고 스프링 빈으로 등록한다. */
    @TestConfiguration
    static class TestBeans {

        @Bean
        FakeAiClient fakeAiClient() {
            return new FakeAiClient();
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }
    }

    /** 응답을 직접 정하는 ai 서비스 대역. 작업 시작 계열은 이 테스트에서 쓰지 않는다. */
    static class FakeAiClient implements PythonAiClient {

        JobStatusResponse response;
        RuntimeException error;
        int calls;

        void reset() {
            response = null;
            error = null;
            calls = 0;
        }

        void respond(AiJobStatus status, String errorMessage) {
            this.response = new JobStatusResponse("ext-1", status, null, errorMessage);
        }

        @Override
        public JobStatusResponse getJobStatus(String jobId) {
            calls++;
            if (error != null) {
                throw error;
            }
            return response;
        }

        @Override
        public JobStartResponse startDocumentPreparation(DocumentPreparationRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JobStartResponse startAnalysis(AnalysisRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public JobStartResponse startPrediction(PredictionRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
