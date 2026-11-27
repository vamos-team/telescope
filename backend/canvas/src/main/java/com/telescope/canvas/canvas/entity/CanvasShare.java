package com.telescope.canvas.canvas.entity;

import com.telescope.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 소유자가 직접 초대한 사람과 그 권한({@code EDIT} / {@code VIEW}). 소유자는 여기에 넣지 않는다. */
@Getter
@Entity
@Table(name = "canvas_share")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CanvasShare extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long canvasId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CanvasRole role;

    private CanvasShare(Long canvasId, Long userId, CanvasRole role) {
        this.canvasId = canvasId;
        this.userId = userId;
        this.role = requireInvitable(role);
    }

    /** 초대 한 건을 만든다. 권한은 {@code EDIT} 또는 {@code VIEW}만 된다. */
    public static CanvasShare of(Long canvasId, Long userId, CanvasRole role) {
        return new CanvasShare(canvasId, userId, role);
    }

    /** 초대한 사람의 권한을 바꾼다. 권한은 {@code EDIT} 또는 {@code VIEW}만 된다. */
    public void changeRole(CanvasRole role) {
        this.role = requireInvitable(role);
    }

    private static CanvasRole requireInvitable(CanvasRole role) {
        if (role != CanvasRole.EDIT && role != CanvasRole.VIEW) {
            throw new IllegalArgumentException("초대 권한은 edit 또는 view만 가능합니다: " + role);
        }
        return role;
    }
}
