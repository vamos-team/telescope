package com.telescope.analysis.datasync.service;

import com.telescope.common.test.TestcontainersConfig;
import com.telescope.analysis.datasync.dto.DataSyncRunResponse;
import com.telescope.analysis.datasync.entity.DataSyncRun;
import com.telescope.analysis.datasync.entity.DataVersion;
import com.telescope.analysis.datasync.repository.DataSyncRunRepository;
import com.telescope.analysis.datasync.repository.DataVersionRepository;
import com.telescope.common.config.JpaAuditingConfig;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.analysis.infra.python.AiCallFailedException;
import com.telescope.analysis.infra.python.AiCallRejectedException;
import com.telescope.analysis.infra.python.AiJobStatus;
import com.telescope.analysis.infra.python.AnalysisRequest;
import com.telescope.analysis.infra.python.DataSyncRequest;
import com.telescope.analysis.infra.python.DocumentPreparationRequest;
import com.telescope.analysis.infra.python.JobStartResponse;
import com.telescope.analysis.infra.python.JobStatusResponse;
import com.telescope.analysis.infra.python.PredictionRequest;
import com.telescope.analysis.infra.python.PythonAiClient;
import com.telescope.analysis.job.entity.Job;
import com.telescope.analysis.job.entity.JobStatus;
import com.telescope.analysis.job.entity.JobType;
import com.telescope.analysis.job.repository.JobRepository;
import com.telescope.analysis.job.service.JobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
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
 * 적재 시작(중복 방지 포함), 결과 확인으로 판 전환, 이력 조회. 실제 PostgreSQL에 커밋되는 상태로 확인한다.
 * ai 서비스는 가짜로 대신한다. 결과 확인은 스케줄러 대신 {@link DataSyncService#pollUnfinished}를 직접 부른다.
 */
@DataJpaTest(properties = {"app.datasync.corp-codes=00159023,00190321", "app.datasync.years=5",
        "app.job.result-save-max-attempts=3"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class, DataSyncService.class, DataSyncResultHandler.class,
        JobService.class, DataSyncServiceTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DataSyncServiceTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    @Autowired DataSyncService service;
    @Autowired DataSyncRunRepository runRepository;
    @Autowired DataVersionRepository versionRepository;
    @Autowired JobRepository jobRepository;
    @Autowired JobService jobService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FakeAi ai;
    @Autowired TransactionTemplate tx;

    Long admin;

    // 사용자는 auth 소관이라(FK 없음) 행을 만들지 않고 id만 쓴다.
    @BeforeEach
    void createAdmin() {
        admin = java.util.concurrent.ThreadLocalRandom.current().nextLong(1000, 1_000_000);
    }

    @BeforeEach
    @AfterEach
    void clean() {
        ai.reset();
        // 테스트끼리 "가장 최근 실행"이 섞이지 않게 비운다(외래 키 순서대로)
        jdbc.update("delete from app.data_version");
        jdbc.update("delete from app.data_sync_run");
        jdbc.update("delete from app.job where job_type = 'DATA_SYNC'");
    }

    @Test
    void 시작하면_실행과_작업을_만들고_대상_회사와_기간을_ai에_보낸다() {
        DataSyncService.StartResult result = service.start(null);

        assertThat(result.started()).isTrue();
        DataSyncRun run = result.run();
        assertThat(run.getSource()).isEqualTo(DataSyncRun.SOURCE_DART_XBRL);
        assertThat(run.getRequestedBy()).isNull();
        assertThat(ai.started).containsExactly(new DataSyncRequest(run.getId().toString(),
                List.of("00159023", "00190321"), TODAY.minusYears(5), TODAY));
        Job job = jobRepository.findById(run.getJobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.getExternalJobId()).isEqualTo("ext-1");
    }

    @Test
    void 진행_중인_실행이_있으면_새로_만들지_않고_그_실행을_돌려준다() {
        DataSyncRun first = service.start(null).run();

        DataSyncService.StartResult again = service.start(7L);

        assertThat(again.started()).isFalse();
        assertThat(again.run().getId()).isEqualTo(first.getId());
        assertThat(runRepository.count()).isEqualTo(1);
        assertThat(ai.started).hasSize(1);
    }

    @Test
    void ai가_시작을_거절하면_작업은_사유와_함께_FAILED이고_다음_시작은_새로_만든다() {
        ai.startError = new AiCallRejectedException("ai 서비스가 아직 지원하지 않는 작업입니다.");
        DataSyncRun failed = service.start(null).run();
        assertThat(jobRepository.findById(failed.getJobId()).orElseThrow().getErrorMessage())
                .isEqualTo("ai 서비스가 아직 지원하지 않는 작업입니다.");

        ai.startError = null;
        DataSyncService.StartResult next = service.start(null);

        assertThat(next.started()).isTrue();
        assertThat(next.run().getId()).isNotEqualTo(failed.getId());
    }

    @Test
    void 시작_응답을_받지_못하면_QUEUED로_두고_다음_확인_때_같은_실행으로_다시_보낸다() {
        ai.startError = new AiCallFailedException("ai 서비스에 작업을 시작하지 못했습니다.");
        DataSyncRun run = service.start(null).run();
        Job waiting = jobRepository.findById(run.getJobId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(waiting.getErrorMessage()).isNull();
        assertThat(service.start(admin).started()).isFalse();  // 접수됐을 수 있으니 새 실행을 막는다

        ai.startError = null;
        service.pollUnfinished();

        assertThat(ai.started).hasSize(2).allSatisfy(r -> assertThat(r.dataSyncRunId()).isEqualTo(run.getId().toString()));
        Job started = jobRepository.findById(run.getJobId()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(started.getExternalJobId()).isNotNull();
    }

    @Test
    void 예상하지_못한_오류도_실패로_확정하지_않고_다시_보낸다() {
        ai.startError = new IllegalStateException("내부 주소 http://ai-server:8000");

        DataSyncRun run = service.start(null).run();

        assertThat(jobRepository.findById(run.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        ai.startError = null;
        service.pollUnfinished();
        assertThat(jobRepository.findById(run.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void 시작_직후_서버가_멈춰_요청을_못_보낸_실행도_다음_확인_때_보낸다() {
        // 커밋까지 하고 ai 요청 전에 멈춘 상태를 그대로 만든다
        DataSyncRun run = tx.execute(status -> runRepository.save(
                DataSyncRun.dartXbrl(jobService.create(JobType.DATA_SYNC).getId(), null)));

        service.pollUnfinished();

        assertThat(ai.started).extracting(DataSyncRequest::dataSyncRunId).containsExactly(run.getId().toString());
        assertThat(jobRepository.findById(run.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void 대상_회사가_없으면_C001로_시작하지_않는다() {
        DataSyncService noCompanies = new DataSyncService(runRepository, versionRepository, jobService, ai, tx, jdbc,
                Clock.systemDefaultZone(), new DataSyncProperties(List.of(), 5));

        assertThatThrownBy(() -> noCompanies.start(null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT));
        assertThat(runRepository.count()).isZero();
    }

    @Test
    void 결과를_확인해_성공했으면_새_판을_켜고_이전_판을_끈다() {
        DataSyncRun first = service.start(null).run();
        ai.status = succeeded("/data/duckdb/finance-" + first.getId() + ".duckdb");
        assertThat(service.pollUnfinished()).isEqualTo(1);
        DataVersion firstVersion = service.activeVersion().orElseThrow();
        assertThat(firstVersion.getDataSyncRunId()).isEqualTo(first.getId());
        assertThat(firstVersion.getFilePath()).endsWith("finance-" + first.getId() + ".duckdb");

        DataSyncRun second = service.start(null).run();
        ai.status = succeeded("/data/duckdb/finance-" + second.getId() + ".duckdb");
        service.pollUnfinished();

        assertThat(service.activeVersion().orElseThrow().getDataSyncRunId()).isEqualTo(second.getId());
        assertThat(versionRepository.findById(firstVersion.getId()).orElseThrow().isActive()).isFalse();
        assertThat(versionRepository.findAll().stream().filter(DataVersion::isActive)).hasSize(1);
        assertThat(service.pollUnfinished()).isZero();  // 끝났으므로 더 묻지 않는다
    }

    @Test
    void 결과에_파일_위치가_없으면_완료하지_않고_다음_확인에서_다시_본다() {
        DataSyncRun run = service.start(null).run();
        ai.status = new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of("filings", 3), null);

        service.pollUnfinished();

        assertThat(jobRepository.findById(run.getJobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(service.activeVersion()).isEmpty();
    }

    @Test
    void ai에서_적재가_실패하면_판은_그대로이고_실패가_남는다() {
        DataSyncRun run = service.start(null).run();
        ai.status = new JobStatusResponse("ext-1", AiJobStatus.FAILED, null, "DART API 키가 설정되지 않았습니다.");

        service.pollUnfinished();

        DataSyncRunResponse shown = service.recentRuns().getFirst();
        assertThat(shown.id()).isEqualTo(run.getId());
        assertThat(shown.status()).isEqualTo(JobStatus.FAILED);
        assertThat(shown.errorMessage()).isEqualTo("DART API 키가 설정되지 않았습니다.");
        assertThat(shown.dataVersionId()).isNull();
        assertThat(service.activeVersion()).isEmpty();
    }

    @Test
    void 같은_순간_두_곳에서_시작해도_실행은_하나만_생긴다() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<DataSyncService.StartResult> a = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.start(null);
            });
            Future<DataSyncService.StartResult> b = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return service.start(admin);
            });
            List<DataSyncService.StartResult> results = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(DataSyncService.StartResult::started)).hasSize(1);
            assertThat(runRepository.count()).isEqualTo(1);
            assertThat(ai.started).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 이력은_최근_실행부터_작업_상태와_만든_판을_함께_보여준다() {
        DataSyncRun first = service.start(null).run();
        ai.status = succeeded("/data/duckdb/finance-1.duckdb");
        service.pollUnfinished();
        DataSyncRun second = service.start(admin).run();

        List<DataSyncRunResponse> runs = service.recentRuns();

        assertThat(runs).extracting(DataSyncRunResponse::id).containsExactly(second.getId(), first.getId());
        assertThat(runs.get(0).requestedBy()).isEqualTo(admin);
        assertThat(runs.get(0).status()).isEqualTo(JobStatus.RUNNING);
        assertThat(runs.get(1).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(runs.get(1).dataVersionId()).isNotNull();
        assertThat(runs.get(1).active()).isTrue();
    }

    private static JobStatusResponse succeeded(String path) {
        return new JobStatusResponse("ext-1", AiJobStatus.SUCCEEDED, Map.of("duckdbPath", path, "filings", 12), null);
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        Clock clock() {
            return Clock.fixed(TODAY.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        FakeAi fakeAi() {
            return new FakeAi();
        }
    }

    /** 적재 시작 응답과 상태 응답을 정할 수 있는 ai 서비스 대역. 동시 호출에 안전하다. */
    static class FakeAi implements PythonAiClient {

        final List<DataSyncRequest> started = new CopyOnWriteArrayList<>();
        final AtomicInteger seq = new AtomicInteger();
        volatile RuntimeException startError;
        volatile JobStatusResponse status;

        void reset() {
            started.clear();
            seq.set(0);
            startError = null;
            status = new JobStatusResponse("ext-1", AiJobStatus.RUNNING, null, null);
        }

        @Override
        public JobStartResponse startDataSync(DataSyncRequest request) {
            started.add(request);
            if (startError != null) {
                throw startError;
            }
            return new JobStartResponse("ext-" + seq.incrementAndGet());
        }

        @Override
        public JobStatusResponse getJobStatus(String jobId) {
            return status;
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
