package com.telescope.analysis.datasync.service;

import com.telescope.analysis.datasync.entity.DataSyncRun;
import com.telescope.analysis.datasync.entity.DataVersion;
import com.telescope.analysis.datasync.repository.DataSyncRunRepository;
import com.telescope.analysis.datasync.repository.DataVersionRepository;
import com.telescope.analysis.job.entity.JobType;
import com.telescope.analysis.job.service.JobResultHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 적재 작업이 끝나면 만든 DuckDB 파일로 새 판을 켠다(#63의 연결 지점).
 *
 * <p>{@code JobService}가 작업 행을 잠근 트랜잭션 안에서 부른다. 이전 판 끄기와 새 판 켜기가 그 트랜잭션에 함께 들어가므로
 * 하나만 되는 일이 없다. 결과에 파일 위치가 없으면 예외를 던져 완료하지 않는다(다음 확인에서 재시도, 한도에 닿으면 실패).
 */
@Component
@RequiredArgsConstructor
public class DataSyncResultHandler implements JobResultHandler {

    private final DataSyncRunRepository runRepository;
    private final DataVersionRepository versionRepository;

    /** 데이터 적재 작업을 맡는다. */
    @Override
    public JobType jobType() {
        return JobType.DATA_SYNC;
    }

    /**
     * 작업에 연결된 실행을 찾아, 이전 활성 판을 끄고 새 판을 켠다.
     *
     * @throws IllegalStateException 실행이 없거나 결과에 DuckDB 파일 위치({@code duckdbPath})가 없을 때
     */
    @Override
    public void saveResult(Long jobId, Object result) {
        DataSyncRun run = runRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("작업에 연결된 적재 실행이 없습니다: jobId=" + jobId));
        if (!(result instanceof Map<?, ?> map) || !(map.get("duckdbPath") instanceof String path) || path.isBlank()) {
            throw new IllegalStateException("적재 결과에 DuckDB 파일 위치가 없습니다: jobId=" + jobId);
        }
        // 활성 판은 하나뿐이라(부분 유일 색인) 끄기를 먼저 DB에 보낸다. Hibernate는 한 번에 보낼 때 추가를 수정보다 먼저 한다
        versionRepository.findByActiveTrue().ifPresent(previous -> {
            previous.deactivate();
            versionRepository.saveAndFlush(previous);
        });
        versionRepository.save(DataVersion.activeOf(run.getId(), path));
    }
}
