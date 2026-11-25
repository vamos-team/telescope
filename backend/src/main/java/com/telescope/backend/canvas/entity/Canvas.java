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

import java.time.LocalDateTime;

/**
 * 캔버스. 소유자는 만든 사람 한 명이며 바뀌지 않는다(소유권 넘기기는 MVP 밖).
 *
 * <p>{@code lastSeq}는 이 캔버스의 마지막 이벤트 번호다. 이벤트 저장은 이 행을 잠근 채로 하므로
 * 같은 캔버스의 이벤트 번호는 겹치지 않고 1씩 늘어난다.
 */
@Getter
@Entity
@Table(name = "canvas")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Canvas extends BaseTimeEntity {

    public static final String UNTITLED = "제목 없는 캔버스";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 20)
    private String quarter;

    @Column(nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private long lastSeq;

    private Long lastEditedBy;

    private LocalDateTime lastEditedAt;

    private LocalDateTime deletedAt;

    private Canvas(String name, String quarter, Long ownerId) {
        this.name = normalizeName(name);
        this.quarter = quarter;
        this.ownerId = ownerId;
    }

    /** 새 캔버스를 만든다. 만든 사람이 소유자가 된다. 이름이 비면 {@link #UNTITLED}. */
    public static Canvas create(String name, String quarter, Long ownerId) {
        return new Canvas(name, quarter, ownerId);
    }

    /** 이름을 바꾼다. 비거나 공백이면 {@link #UNTITLED}. */
    public void rename(String name) {
        this.name = normalizeName(name);
    }

    /** 다음 이벤트 번호를 발급한다. 이 행을 잠근 트랜잭션 안에서만 부른다. */
    public long nextSeq() {
        return ++lastSeq;
    }

    /** 마지막으로 고친 사람과 시각을 남긴다. 캔버스 목록의 최근 수정 순 정렬에 쓴다. */
    public void markEdited(Long userId, LocalDateTime now) {
        this.lastEditedBy = userId;
        this.lastEditedAt = now;
    }

    /** 삭제 표시를 한다. 행은 남겨 두어 이 캔버스로 만든 결과물이 참조를 잃지 않게 한다. */
    public void delete(LocalDateTime now) {
        this.deletedAt = now;
    }

    /** 삭제 표시된 캔버스인지. 삭제된 캔버스는 없는 캔버스(404)처럼 다룬다. */
    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static String normalizeName(String name) {
        return name == null || name.isBlank() ? UNTITLED : name.trim();
    }
}
