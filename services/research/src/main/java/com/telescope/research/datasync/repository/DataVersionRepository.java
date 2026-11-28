package com.telescope.research.datasync.repository;

import com.telescope.research.datasync.entity.DataVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 재무 데이터 판 저장소. */
public interface DataVersionRepository extends JpaRepository<DataVersion, Long> {

    /** 지금 활성인 판. 아직 적재가 성공한 적이 없으면 없다. */
    Optional<DataVersion> findByActiveTrue();

    /** 실행들이 만든 판(목록 화면에서 실행마다 판을 붙일 때). */
    List<DataVersion> findByDataSyncRunIdIn(Collection<Long> dataSyncRunIds);
}
