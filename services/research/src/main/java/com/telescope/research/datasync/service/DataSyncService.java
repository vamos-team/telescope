package com.telescope.research.datasync.service;

import com.telescope.research.datasync.dto.DataSyncRunResponse;
import com.telescope.research.datasync.entity.DataSyncRun;
import com.telescope.research.datasync.entity.DataVersion;
import com.telescope.research.datasync.repository.DataSyncRunRepository;
import com.telescope.research.datasync.repository.DataVersionRepository;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.research.infra.python.AiCallRejectedException;
import com.telescope.research.infra.python.DataSyncRequest;
import com.telescope.research.infra.python.PythonAiClient;
import com.telescope.research.job.dto.JobResponse;
import com.telescope.research.job.entity.Job;
import com.telescope.research.job.entity.JobType;
import com.telescope.research.job.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재무 데이터 적재 배치의 시작·결과 확인·이력 조회. 설계와 결정 이유는 {@code docs/backend/datasync.md}.
 *
 * <p>시작 순서({@code job.md} 3.3 "다른 도메인이 작업을 시작하는 순서"를 따른다):
 * <ol>
 *   <li>한 트랜잭션: DB 잠금 → 가장 최근 실행이 끝나지 않았으면 그것을 돌려주고 끝 → {@code Job}(QUEUED) + {@link DataSyncRun}</li>
 *   <li>ai/server에 적재 작업 시작(트랜잭션 밖). 접수되면 {@code markStarted}. ai가 거절했을 때만 {@code markFailed}</li>
 * </ol>
 * 접수됐는지 모르는 경우(응답을 못 받음, 받은 번호를 저장하지 못함, 그 사이 서버가 멈춤)는 작업을 QUEUED로 두고
 * {@link #pollUnfinished}가 같은 요청을 다시 보낸다. ai/server는 같은 실행 번호의 요청에 같은 작업을 돌려주므로
 * (멱등, {@code ai-contract.md} 2.13) 다시 보내도 적재가 두 번 돌지 않는다. 설계는 {@code datasync.md} S12.
 * 결과 확인은 화면이 폴링하지 않으므로 {@link #pollUnfinished}를 스케줄러가 주기적으로 부른다. 끝난 작업의 결과는
 * {@link DataSyncResultHandler}가 새 판으로 반영한다(#63).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(DataSyncProperties.class)
public class DataSyncService {

    /** 적재 시작을 한 줄로 세우는 PostgreSQL advisory lock 번호(트랜잭션이 끝나면 풀린다). */
    static final long START_LOCK_KEY = 135_001L;
    private final DataSyncRunRepository runRepository;
    private final DataVersionRepository versionRepository;
    private final JobService jobService;
    private final PythonAiClient aiClient;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final DataSyncProperties properties;

    /**
     * 적재를 시작한다. 이미 끝나지 않은 실행이 있으면 새로 만들지 않고 그 실행을 돌려준다.
     *
     * @param requestedBy 직접 실행한 관리자. 스케줄러가 시작하면 null
     * @return 시작 결과. {@code started}가 false면 이미 진행 중인 실행이다
     * @throws BusinessException 대상 회사가 설정되지 않았으면 {@code C001}
     */
    public StartResult start(Long requestedBy) {
        if (properties.corpCodes().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "적재할 회사(app.datasync.corp-codes)가 설정되지 않았습니다.");
        }
        StartResult created = Objects.requireNonNull(tx.execute(status -> createRunUnlessRunning(requestedBy)));
        if (!created.started()) {
            return created;
        }
        send(created.run());
        return created;
    }

    /**
     * 끝나지 않은 적재 작업의 ai 상태를 확인해 반영한다. 화면이 폴링하지 않는 배치라 스케줄러가 부른다.
     * 한 작업의 확인이 실패해도 나머지는 계속 확인한다(일시 오류는 다음 주기에 다시).
     * 먼저 ai 접수 번호가 없는 작업(시작 직후 서버가 멈춤 등)의 요청을 다시 보낸다. 그러지 않으면 그 실행이 끝나지 않아
     * 다음 적재가 영영 시작되지 않는다.
     *
     * @return 확인한 작업 수
     */
    public int pollUnfinished() {
        for (Long jobId : jobService.unsentJobIds(JobType.DATA_SYNC)) {
            runRepository.findByJobId(jobId).ifPresent(this::send);
        }
        List<Long> jobIds = jobService.unfinishedJobIds(JobType.DATA_SYNC);
        for (Long jobId : jobIds) {
            try {
                jobService.getJob(jobId);
            } catch (RuntimeException e) {
                log.warn("적재 작업 상태 확인 실패: jobId={}, reason={}", jobId, e.getClass().getSimpleName());
            }
        }
        return jobIds.size();
    }

    /**
     * 최근 실행 20건을 작업 상태·만든 판과 함께 돌려준다(관리자 화면). ai를 부르지 않는다.
     * 작업과 판을 따로 읽으므로 같은 스냅샷에서 읽는다(REPEATABLE READ). 그러지 않으면 두 조회 사이에 결과 확인이
     * 커밋돼 "진행 중인데 판이 켜진" 응답이 나올 수 있다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DataSyncRunResponse> recentRuns() {
        List<DataSyncRun> runs = runRepository.findTop20ByOrderByIdDesc();
        Map<Long, JobResponse> jobs = jobService.peek(runs.stream().map(DataSyncRun::getJobId).toList());
        Map<Long, DataVersion> versions = versionRepository
                .findByDataSyncRunIdIn(runs.stream().map(DataSyncRun::getId).toList()).stream()
                .collect(Collectors.toMap(DataVersion::getDataSyncRunId, Function.identity()));
        return runs.stream().map(r -> toResponse(r, jobs.get(r.getJobId()), versions.get(r.getId()))).toList();
    }

    /** 실행 하나를 작업 상태·만든 판과 함께 응답으로 바꾼다. ai를 부르지 않는다. 같은 스냅샷에서 읽는다({@link #recentRuns}). */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DataSyncRunResponse describe(DataSyncRun run) {
        JobResponse job = jobService.peek(List.of(run.getJobId())).get(run.getJobId());
        DataVersion version = versionRepository.findByDataSyncRunIdIn(List.of(run.getId())).stream().findFirst().orElse(null);
        return toResponse(run, job, version);
    }

    /** 지금 활성인 판. 분석은 시작할 때 이 판 번호를 기록한다. 적재가 성공한 적이 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<DataVersion> activeVersion() {
        return versionRepository.findByActiveTrue();
    }

    /**
     * DB 잠금을 잡고, 가장 최근 실행이 끝났을 때만 새 실행과 작업을 만든다. 같은 순간 두 곳(스케줄러, 관리자)이 시작해도
     * 잠금 때문에 한 줄로 서고, 뒤에 온 쪽은 앞에서 만든 실행을 보고 돌아간다.
     */
    private StartResult createRunUnlessRunning(Long requestedBy) {
        jdbc.queryForObject("select pg_advisory_xact_lock(?)", Object.class, START_LOCK_KEY);
        Optional<DataSyncRun> latest = runRepository.findTopByOrderByIdDesc();
        if (latest.isPresent() && !jobService.isFinished(latest.get().getJobId())) {
            return new StartResult(latest.get(), false);
        }
        Job job = jobService.create(JobType.DATA_SYNC);
        return new StartResult(runRepository.save(DataSyncRun.dartXbrl(job.getId(), requestedBy)), true);
    }

    /**
     * 실행 하나의 적재 요청을 ai에 보낸다(트랜잭션 밖). 처음 시작할 때와 접수 번호가 없는 작업을 다시 보낼 때 쓴다.
     *
     * <ul>
     *   <li>ai가 거절했다({@link AiCallRejectedException}): 접수되지 않은 것이 확실하므로 사유와 함께 실패로 끝낸다.</li>
     *   <li>응답을 받지 못했다(그 밖의 예외): 접수됐는지 모르므로 QUEUED로 두고 다음 확인 때 다시 보낸다.</li>
     *   <li>접수됐지만 번호를 저장하지 못했다: 실패로 끝내지 않는다. 다시 보내면 ai가 같은 번호를 돌려준다.</li>
     * </ul>
     * 날짜는 보낼 때 다시 계산하지만, ai는 같은 실행 번호의 두 번째 요청 내용을 쓰지 않으므로 처음 기간이 유지된다.
     */
    private void send(DataSyncRun run) {
        LocalDate today = LocalDate.now(clock);
        String externalJobId;
        try {
            externalJobId = aiClient.startDataSync(new DataSyncRequest(run.getId().toString(),
                    properties.corpCodes(), today.minusYears(properties.years()), today)).jobId();
        } catch (AiCallRejectedException e) {
            try {
                jobService.markFailed(run.getJobId(), e.getMessage());
            } catch (RuntimeException saveError) {
                // QUEUED로 남으면 다음 확인 때 다시 보내고, ai는 같은 거절을 돌려준다
                log.warn("적재 시작 거절 저장 실패: runId={}, reason={}", run.getId(), saveError.getClass().getSimpleName());
            }
            return;
        } catch (RuntimeException e) {
            log.warn("적재 시작 응답을 받지 못함, 다음 확인 때 다시 보냄: runId={}, reason={}", run.getId(), e.getClass().getSimpleName());
            return;
        }
        try {
            jobService.markStarted(run.getJobId(), externalJobId);
        } catch (RuntimeException e) {
            log.warn("적재 접수 번호 저장 실패, 다음 확인 때 다시 보냄: runId={}, reason={}", run.getId(), e.getClass().getSimpleName());
        }
    }

    /** 실행을 관리자 화면 응답으로 바꾼다. 작업이 지워졌으면 상태는 비워 둔다. */
    private static DataSyncRunResponse toResponse(DataSyncRun run, JobResponse job, DataVersion version) {
        return new DataSyncRunResponse(run.getId(), run.getSource(), run.getRequestedBy(), run.getCreatedAt(),
                run.getJobId(),
                job == null ? null : job.status(),
                job == null ? null : job.startedAt(),
                job == null ? null : job.finishedAt(),
                job == null ? null : job.errorMessage(),
                version == null ? null : version.getId(),
                version != null && version.isActive());
    }

    /**
     * 시작 결과.
     *
     * @param run     새로 만든 실행 또는 이미 진행 중인 실행
     * @param started 새로 시작했으면 true
     */
    public record StartResult(DataSyncRun run, boolean started) {
    }
}
