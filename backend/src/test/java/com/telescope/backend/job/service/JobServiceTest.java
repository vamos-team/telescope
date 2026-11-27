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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서비스가 트랜잭션 경계를 직접 다루므로(ai 호출은 트랜잭션 밖) 테스트 전체를 감싸는
 * 트랜잭션을 끄고, 실제로 커밋되는 상태에서 검증한다. ai 서비스는 고정 응답을 주는 가짜로 대신한다.
 */
@DataJpaTest(properties = "app.job.result-save-max-attempts=3")
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

    @Autowired
    FakeResultHandler resultHandler;

    @BeforeEach
    void setUp() {
        aiClient.reset();
        resultHandler.reset();
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
        assertThat(aiClient.calls.get()).isZero();
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
        assertThat(aiClient.calls.get()).isEqualTo(2);
    }

    @Test
    void 종료된_작업은_ai를_다시_호출하지_않는다() {
        Long id = startedJob();
        aiClient.respond(AiJobStatus.SUCCEEDED, null);
        jobService.getJob(id);

        jobService.getJob(id);
        jobService.getJob(id);

        assertThat(aiClient.calls.get()).isEqualTo(1);
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

    @Test
    void 같은_작업을_동시에_조회해도_먼저_종료된_상태가_유지된다() throws Exception {
        Long id = startedJob();
        // 두 요청이 같은 순간 서로 다른 종료 결과를 받는 상황. 잠금이 없으면 나중 커밋이 앞선 결과를 덮어쓴다.
        aiClient.barrier = new CyclicBarrier(2);
        aiClient.sequence = List.of(
                new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, null, null),
                new JobStatusResponse("ext-1", AiJobStatus.FAILED, null, "늦게 온 실패"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JobResponse> first = pool.submit(() -> jobService.getJob(id));
            Future<JobResponse> second = pool.submit(() -> jobService.getJob(id));
            JobResponse firstResult = first.get(10, TimeUnit.SECONDS);
            JobResponse secondResult = second.get(10, TimeUnit.SECONDS);

            JobResponse saved = JobResponse.from(jobRepository.findById(id).orElseThrow());
            assertThat(firstResult).isEqualTo(saved);
            assertThat(secondResult).isEqualTo(saved);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 결과를_저장한_뒤에_완료로_확정한다() {
        Long id = startedJob();
        aiClient.response = new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of("summary", "요약"), null);

        JobResponse response = jobService.getJob(id);

        assertThat(response.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(resultHandler.saved).containsExactly(Map.of("summary", "요약"));
    }

    @Test
    void 결과_저장이_실패하면_완료하지_않고_다음_조회에서_다시_시도한다() {
        Long id = startedJob();
        aiClient.response = new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of("summary", "요약"), null);
        resultHandler.failuresLeft.set(1);

        JobResponse first = jobService.getJob(id);
        assertThat(first.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobRepository.findById(id).orElseThrow().getResultSaveFailures()).isEqualTo(1);
        assertThat(resultHandler.saved).isEmpty();

        JobResponse second = jobService.getJob(id);
        assertThat(second.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(resultHandler.saved).containsExactly(Map.of("summary", "요약"));
        assertThat(aiClient.calls.get()).isEqualTo(2);
    }

    @Test
    void 결과_저장이_실패하면_처리기가_쓴_내용도_함께_되돌린다() {
        Long id = startedJob();
        long before = jobRepository.count();
        aiClient.respond(AiJobStatus.SUCCEEDED, null);
        resultHandler.failuresLeft.set(1);
        resultHandler.writeBeforeFailing = true;

        jobService.getJob(id);

        assertThat(jobRepository.count()).isEqualTo(before);
    }

    @Test
    void 결과_저장이_한도만큼_실패하면_FAILED로_끝낸다() {
        Long id = startedJob();
        aiClient.respond(AiJobStatus.SUCCEEDED, null);
        resultHandler.failuresLeft.set(10);

        jobService.getJob(id);
        jobService.getJob(id);
        JobResponse third = jobService.getJob(id);

        assertThat(third.status()).isEqualTo(JobStatus.FAILED);
        assertThat(third.errorMessage()).isEqualTo(JobService.RESULT_SAVE_FAILED_MESSAGE);
        // 끝났으므로 더 묻지 않는다
        jobService.getJob(id);
        assertThat(aiClient.calls.get()).isEqualTo(3);
    }

    @Test
    void 처리기가_없는_작업_종류는_결과를_저장하지_않고_완료한다() {
        Job job = jobService.create(JobType.DOCUMENT_PREPARATION);
        jobService.markStarted(job.getId(), "ext-1");
        aiClient.respond(AiJobStatus.SUCCEEDED, null);

        assertThat(jobService.getJob(job.getId()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(resultHandler.saved).isEmpty();
    }

    @Test
    void 같은_작업이_동시에_완료돼도_결과는_한_번만_저장된다() throws Exception {
        Long id = startedJob();
        aiClient.barrier = new CyclicBarrier(2);
        aiClient.response = new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of("n", 1), null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JobResponse> first = pool.submit(() -> jobService.getJob(id));
            Future<JobResponse> second = pool.submit(() -> jobService.getJob(id));
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(second.get(10, TimeUnit.SECONDS).status()).isEqualTo(JobStatus.SUCCEEDED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(resultHandler.saved).hasSize(1);
    }

    @Test
    void 같은_작업_종류의_처리기가_둘이면_만들_수_없다() {
        FakeResultHandler a = new FakeResultHandler(null);
        FakeResultHandler b = new FakeResultHandler(null);

        assertThatThrownBy(() -> new JobService(jobRepository, aiClient, null, null, List.of(a, b), 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANALYSIS");
    }

    @Test
    void 재시도_한도는_1_이상이어야_한다() {
        assertThatThrownBy(() -> new JobService(jobRepository, aiClient, null, null, List.of(), 0))
                .isInstanceOf(IllegalStateException.class);
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
        FakeResultHandler fakeResultHandler(JobRepository jobRepository) {
            return new FakeResultHandler(jobRepository);
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

    /**
     * 분석 작업의 결과 처리기 대역. 저장한 결과를 모으고, 정한 횟수만큼 실패한다.
     * {@code writeBeforeFailing}이면 실패하기 전에 DB에 한 줄을 써서, 실패 시 함께 되돌려지는지 확인한다.
     */
    static class FakeResultHandler implements JobResultHandler {

        final List<Object> saved = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile boolean writeBeforeFailing;
        private final JobRepository jobRepository;

        FakeResultHandler(JobRepository jobRepository) {
            this.jobRepository = jobRepository;
        }

        void reset() {
            saved.clear();
            failuresLeft.set(0);
            writeBeforeFailing = false;
        }

        @Override
        public JobType jobType() {
            return JobType.ANALYSIS;
        }

        @Override
        public void saveResult(Long jobId, Object result) {
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                if (writeBeforeFailing) {
                    jobRepository.saveAndFlush(Job.create(JobType.PREDICTION));
                }
                throw new IllegalStateException("결과 저장 실패(테스트)");
            }
            saved.add(result == null ? "null" : result);
        }
    }

    /** 응답을 직접 정하는 ai 서비스 대역. 작업 시작 계열은 이 테스트에서 쓰지 않는다. */
    static class FakeAiClient implements PythonAiClient {

        JobStatusResponse response;
        RuntimeException error;
        final AtomicInteger calls = new AtomicInteger();
        /** 값이 있으면 호출 순서대로 이 응답을 준다. */
        List<JobStatusResponse> sequence;
        /** 값이 있으면 모든 호출이 여기서 만나 동시에 응답을 받는다. */
        CyclicBarrier barrier;

        void reset() {
            response = null;
            error = null;
            calls.set(0);
            sequence = null;
            barrier = null;
        }

        void respond(AiJobStatus status, String errorMessage) {
            this.response = new JobStatusResponse("ext-1", status, null, errorMessage);
        }

        @Override
        public JobStatusResponse getJobStatus(String jobId) {
            int order = calls.incrementAndGet();
            if (barrier != null) {
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            if (error != null) {
                throw error;
            }
            return sequence != null ? sequence.get(order - 1) : response;
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

        @Override
        public JobStartResponse startDataSync(com.telescope.backend.infra.python.DataSyncRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
