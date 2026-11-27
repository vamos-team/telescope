package com.telescope.backend.canvas.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 계정 대응 확정({@code confirm_mapping}) 결과. (캔버스, 개념, 회사)마다 마지막 결정 하나만 둔다.
 * 표 비교를 ai에 요청할 때 backend가 이 값을 함께 넘긴다.
 */
@Getter
@Entity
@Table(name = "canvas_mapping")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasMapping extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long canvasId;

    @Column(nullable = false, length = 100)
    private String conceptId;

    @Column(nullable = false, length = 50)
    private String company;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String cells;

    @Column(nullable = false)
    private boolean accepted;

    @Column(nullable = false)
    private Long decidedBy;

    private CanvasMapping(Long canvasId, String conceptId, String company, String cellsJson,
                          boolean accepted, Long decidedBy) {
        this.canvasId = canvasId;
        this.conceptId = conceptId;
        this.company = company;
        this.cells = cellsJson;
        this.accepted = accepted;
        this.decidedBy = decidedBy;
    }

    /** 처음 내린 대응 결정을 만든다. */
    public static CanvasMapping create(Long canvasId, String conceptId, String company, String cellsJson,
                                       boolean accepted, Long decidedBy) {
        return new CanvasMapping(canvasId, conceptId, company, cellsJson, accepted, decidedBy);
    }

    /** 같은 (개념, 회사)에 대한 결정을 새 결정으로 바꾼다. 나중 결정이 이긴다. */
    public void decide(String cellsJson, boolean accepted, Long decidedBy) {
        this.cells = cellsJson;
        this.accepted = accepted;
        this.decidedBy = decidedBy;
    }
}
