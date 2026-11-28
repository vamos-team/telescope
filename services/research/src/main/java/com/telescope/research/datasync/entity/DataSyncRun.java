package com.telescope.research.datasync.entity;

import com.telescope.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 재무 데이터 적재 실행 한 번(작업 일지). 실행 상태·시각은 {@code jobId}의 {@code Job}에서 읽는다(V14).
 * 만든 뒤 바뀌지 않는다.
 */
@Getter
@Entity
@Table(name = "data_sync_run")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataSyncRun extends BaseTimeEntity {

    /** 지금 적재하는 원천. 정기공시 XBRL 하나뿐이다. */
    public static final String SOURCE_DART_XBRL = "dart_xbrl";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false)
    private Long jobId;

    /** 직접 실행한 관리자. 스케줄러가 시작했으면 비어 있다. */
    private Long requestedBy;

    private DataSyncRun(String source, Long jobId, Long requestedBy) {
        this.source = source;
        this.jobId = jobId;
        this.requestedBy = requestedBy;
    }

    /** 정기공시 XBRL 적재 실행을 만든다. */
    public static DataSyncRun dartXbrl(Long jobId, Long requestedBy) {
        return new DataSyncRun(SOURCE_DART_XBRL, jobId, requestedBy);
    }
}
