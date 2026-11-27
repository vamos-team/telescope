package com.telescope.backend.canvas.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 캔버스에서 한 사람이 가진 권한.
 *
 * <p>{@code OWNER}는 캔버스를 만든 사람 한 명이며 {@code canvas.owner_id}로만 정해진다.
 * 공유 명단({@code canvas_share})에는 {@code EDIT}, {@code VIEW}만 저장한다.
 * API에서는 소문자({@code owner} / {@code edit} / {@code view})로 주고받는다.
 */
public enum CanvasRole {
    OWNER, EDIT, VIEW;

    /** 카드·연결 조작, 초안·질문 생성을 할 수 있는지. */
    public boolean canEdit() {
        return this == OWNER || this == EDIT;
    }

    /** API에서 쓰는 소문자 값을 돌려준다. */
    @JsonValue
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** API의 소문자 값을 권한으로 바꾼다. 모르는 값이면 {@code IllegalArgumentException}. */
    @JsonCreator
    public static CanvasRole fromApiValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
