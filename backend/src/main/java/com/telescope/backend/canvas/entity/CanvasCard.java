package com.telescope.backend.canvas.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * 캔버스 카드. ID는 화면이 만든 UUID다(여러 사람이 동시에 만들어도 겹치지 않게).
 *
 * <p>여러 사람이 같은 카드를 동시에 고쳐도 서로 덮어쓰지 않도록 위치({@code x}, {@code y}),
 * 메모 카드 본문({@code text}), 카드 메모({@code memo})를 다른 컬럼에 둔다.
 * 두 글은 각각 버전을 갖고, 고칠 때 기준 버전이 다르면 서비스가 충돌(409)로 거절한다.
 * 종류별 필드(표 ID, 행·열, 수정값 등)는 {@code props}(JSONB 문자열)에 둔다.
 */
@Getter
@Entity
@Table(name = "canvas_card")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasCard extends BaseTimeEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long canvasId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardKind kind;

    @Column(nullable = false)
    private double x;

    @Column(nullable = false)
    private double y;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String props;

    private String text;

    @Column(nullable = false)
    private int textVersion;

    /** 카드 메모(메모 카드 제외). 비어 있으면 없음. */
    private String memo;

    @Column(nullable = false)
    private int memoVersion;

    @Column(nullable = false)
    private Long createdBy;

    /** 화면이 ID를 정하므로 저장 전에 새 행인지 직접 알려 준다(불필요한 조회·merge를 막음). */
    @Transient
    private boolean isNew = true;

    private CanvasCard(UUID id, Long canvasId, CardKind kind, double x, double y, String propsJson, Long createdBy) {
        this.id = id;
        this.canvasId = canvasId;
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.props = propsJson;
        this.createdBy = createdBy;
    }

    /** 새 카드를 만든다. 글은 비어 있고 버전은 0이다. */
    public static CanvasCard create(UUID id, Long canvasId, CardKind kind, double x, double y,
                                    String propsJson, Long createdBy) {
        return new CanvasCard(id, canvasId, kind, x, y, propsJson, createdBy);
    }

    /** 위치만 바꾼다. 글과 버전은 건드리지 않는다(나중에 저장된 위치가 이긴다). */
    public void moveTo(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /** 종류별 필드를 통째로 바꾼다. 글과 위치는 건드리지 않는다. */
    public void replaceProps(String propsJson) {
        this.props = propsJson;
    }

    /** 메모 카드 본문을 바꾸고 버전을 1 올린다. 기준 버전 확인은 부르는 쪽이 먼저 한다. */
    public int changeText(String text) {
        this.text = text;
        return ++textVersion;
    }

    /** 카드 메모를 바꾸고 버전을 1 올린다. 빈 값이면 지운다. 기준 버전 확인은 부르는 쪽이 먼저 한다. */
    public int changeMemo(String memo) {
        this.memo = memo == null || memo.isBlank() ? null : memo;
        return ++memoVersion;
    }

    /** {@link Persistable}: 새로 만든 객체면 true. 저장되거나 조회된 뒤에는 false. */
    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
