package com.telescope.canvas.canvas.entity;

import com.telescope.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

/**
 * 카드 사이 연결선. ID는 화면이 만든 UUID다. 양 끝 카드가 지워지면 DB가 함께 지운다.
 * 선 메모({@code memo})는 버전을 갖고, 기준 버전이 다르면 서비스가 충돌(409)로 거절한다.
 */
@Getter
@Entity
@Table(name = "canvas_link")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasLink extends BaseTimeEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long canvasId;

    @Column(nullable = false)
    private UUID sourceCardId;

    @Column(nullable = false)
    private UUID targetCardId;

    /** 선 메모. 비어 있으면 없음. 관계 종류(근거·설명·반박)는 저장하지 않고 분석에서 판단한다(#123). */
    private String memo;

    @Column(nullable = false)
    private int memoVersion;

    /** 선 메모를 마지막으로 고친 사람. 메모를 지우면 비운다. */
    private Long memoBy;

    @Column(nullable = false)
    private Long createdBy;

    @Transient
    private boolean isNew = true;

    private CanvasLink(UUID id, Long canvasId, UUID sourceCardId, UUID targetCardId, Long createdBy) {
        this.id = id;
        this.canvasId = canvasId;
        this.sourceCardId = sourceCardId;
        this.targetCardId = targetCardId;
        this.createdBy = createdBy;
    }

    /** 새 연결을 만든다. 선 메모는 비어 있고 버전은 0이다. */
    public static CanvasLink create(UUID id, Long canvasId, UUID sourceCardId, UUID targetCardId, Long createdBy) {
        return new CanvasLink(id, canvasId, sourceCardId, targetCardId, createdBy);
    }

    /**
     * 선 메모를 바꾸고 버전을 1 올린다. 고친 사람을 작성자로 남기고, 빈 값이면 메모와 작성자를 지운다.
     * 기준 버전 확인은 부르는 쪽이 먼저 한다.
     */
    public int changeMemo(String memo, Long editedBy) {
        boolean blank = memo == null || memo.isBlank();
        this.memo = blank ? null : memo;
        this.memoBy = blank ? null : editedBy;
        return ++memoVersion;
    }

    /** 이 연결이 주어진 카드에 닿는지. 카드를 지울 때 같이 지울 연결을 고른다. */
    public boolean touches(UUID cardId) {
        return sourceCardId.equals(cardId) || targetCardId.equals(cardId);
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
