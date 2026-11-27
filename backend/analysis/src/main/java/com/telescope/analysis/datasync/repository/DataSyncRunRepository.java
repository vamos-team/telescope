package com.telescope.analysis.datasync.repository;

import com.telescope.analysis.datasync.entity.DataSyncRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 적재 실행 저장소. */
public interface DataSyncRunRepository extends JpaRepository<DataSyncRun, Long> {

    /** 가장 최근 실행. 이게 끝나지 않았으면 새로 시작하지 않는다. */
    Optional<DataSyncRun> findTopByOrderByIdDesc();

    /** 작업에 연결된 실행. 작업 하나에 실행 하나다({@code uk_data_sync_run_job}). */
    Optional<DataSyncRun> findByJobId(Long jobId);

    /** 최근 실행 목록(관리자 화면). */
    List<DataSyncRun> findTop20ByOrderByIdDesc();
}
