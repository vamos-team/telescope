package com.telescope.backend.canvas.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
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

import java.util.Set;
import java.util.UUID;

/**
 * 카드 사이 연결선. ID는 화면이 만든 UUID다. 양 끝 카드가 지워지면 DB가 함께 지운다.
 * 선 프롬프트({@code prompt})는 버전을 갖고, 기준 버전이 다르면 서비스가 충돌(409)로 거절한다.
 */
@Getter
@Entity
@Table(name = "canvas_link")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasLink extends BaseTimeEntity implements Persistable<UUID> {

    /** 연결 관계로 쓸 수 있는 값. */
    public static final Set<String> RELATIONS = Set.of("근거", "설명", "반박", "관련");

    @Id
    private UUID id;

    @Column(nullable = false)
    private Long canvasId;

    @Column(nullable = false)
    private UUID sourceCardId;

    @Column(nullable = false)
    private UUID targetCardId;

    @Column(nullable = false, length = 10)
    private String rel;

    private String prompt;

    @Column(nullable = false)
    private int promptVersion;

    @Column(nullable = false)
    private Long createdBy;

    @Transient
    private boolean isNew = true;

    private CanvasLink(UUID id, Long canvasId, UUID sourceCardId, UUID targetCardId, String rel, Long createdBy) {
        this.id = id;
        this.canvasId = canvasId;
        this.sourceCardId = sourceCardId;
        this.targetCardId = targetCardId;
        this.rel = requireRelation(rel);
        this.createdBy = createdBy;
    }

    /** 새 연결을 만든다. 관계는 {@link #RELATIONS} 중 하나여야 한다. */
    public static CanvasLink create(UUID id, Long canvasId, UUID sourceCardId, UUID targetCardId,
                                    String rel, Long createdBy) {
        return new CanvasLink(id, canvasId, sourceCardId, targetCardId, rel, createdBy);
    }

    /** 연결 관계를 바꾼다. 선 프롬프트는 건드리지 않는다. */
    public void changeRelation(String rel) {
        this.rel = requireRelation(rel);
    }

    /** 선 프롬프트를 바꾸고 버전을 1 올린다. 빈 값이면 지운다. 기준 버전 확인은 부르는 쪽이 먼저 한다. */
    public int changePrompt(String prompt) {
        this.prompt = prompt == null || prompt.isBlank() ? null : prompt;
        return ++promptVersion;
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

    private static String requireRelation(String rel) {
        if (!RELATIONS.contains(rel)) {
            throw new IllegalArgumentException("알 수 없는 연결 관계입니다: " + rel);
        }
        return rel;
    }
}
