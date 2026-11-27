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
 * 캔버스 이벤트 로그 한 줄. 캔버스 단위로 {@code seq}가 1씩 늘어나고 고치지 않는다.
 * 활동 로그와 다른 사람에게 보낼 변경 메시지를 이 기록에서 만든다.
 */
@Getter
@Entity
@Table(name = "canvas_event")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long canvasId;

    @Column(nullable = false)
    private long seq;

    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false)
    private Long authorId;

    @Column(nullable = false, length = 40)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    private CanvasEvent(Long canvasId, long seq, UUID requestId, Long authorId, String type, String payloadJson) {
        this.canvasId = canvasId;
        this.seq = seq;
        this.requestId = requestId;
        this.authorId = authorId;
        this.type = type;
        this.payload = payloadJson;
    }

    /** 반영된 이벤트 한 줄을 기록한다. {@code seq}는 캔버스 행을 잠근 채 발급한 값이어야 한다. */
    public static CanvasEvent of(Long canvasId, long seq, UUID requestId, Long authorId, String type, String payloadJson) {
        return new CanvasEvent(canvasId, seq, requestId, authorId, type, payloadJson);
    }
}
