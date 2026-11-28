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
 * 쓸 수 있는 재무 데이터 판 하나. 적재가 성공했을 때만 생긴다. 판마다 DuckDB 파일이 따로 있다({@code filePath}).
 *
 * <p>활성 판은 언제나 하나다(DB 부분 유일 색인). 새 판을 켤 때 이전 판을 먼저 끈다. 판 파일 위치는 바뀌지 않는다.
 */
@Getter
@Entity
@Table(name = "data_version")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataVersion extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long dataSyncRunId;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(nullable = false, length = 500)
    private String filePath;

    private DataVersion(Long dataSyncRunId, String filePath) {
        this.dataSyncRunId = dataSyncRunId;
        this.filePath = filePath;
        this.active = true;
    }

    /** 실행이 만든 파일로 새 활성 판을 만든다. 이전 활성 판은 부르는 쪽이 먼저 끈다. */
    public static DataVersion activeOf(Long dataSyncRunId, String filePath) {
        return new DataVersion(dataSyncRunId, filePath);
    }

    /** 이 판을 끈다(새 판이 켜질 때). */
    public void deactivate() {
        this.active = false;
    }
}
