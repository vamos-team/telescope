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

import java.util.UUID;

/**
 * 처리한 이벤트 묶음 요청. (캔버스, {@code requestId})마다 한 줄이다.
 * 화면이 응답을 못 받고 같은 묶음을 다시 보내면 다시 저장하지 않고 {@code response}를 그대로 돌려준다.
 */
@Getter
@Entity
@Table(name = "canvas_request")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long canvasId;

    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private Long authorId;

    /** 이 묶음으로 저장된 첫 이벤트 번호. 저장된 이벤트가 없으면 null. */
    private Long firstSeq;

    @Column(nullable = false)
    private long lastSeq;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String response;

    private CanvasRequest(Long canvasId, UUID requestId, Long authorId, Long firstSeq, long lastSeq, String responseJson) {
        this.canvasId = canvasId;
        this.requestId = requestId;
        this.authorId = authorId;
        this.firstSeq = firstSeq;
        this.lastSeq = lastSeq;
        this.response = responseJson;
    }

    /** 처리를 마친 묶음과 그 응답을 기록한다. */
    public static CanvasRequest of(Long canvasId, UUID requestId, Long authorId, Long firstSeq, long lastSeq,
                                   String responseJson) {
        return new CanvasRequest(canvasId, requestId, authorId, firstSeq, lastSeq, responseJson);
    }
}
