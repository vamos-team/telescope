package com.telescope.backend.canvas.entity;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 카드 종류. 종류별 필드는 {@code canvas_card.props}(JSONB)에 둔다.
 *
 * <ul>
 *   <li>{@code TABLE}: 공시 표 (tableId, rows, cols, vcol, edits, sort)</li>
 *   <li>{@code COMPARE}: 표 비교 (sources)</li>
 *   <li>{@code DOC}: IR·AR·뉴스·사내 자료 (docId)</li>
 *   <li>{@code PROMPT}: 프롬프트 카드. 본문은 {@code text} 컬럼</li>
 *   <li>{@code DASH}: 대시보드 (panel)</li>
 *   <li>{@code CHART}: 시각화 (compareId)</li>
 * </ul>
 */
public enum CardKind {
    TABLE, COMPARE, DOC, PROMPT, DASH, CHART;

    /** API에서 쓰는 소문자 값을 돌려준다. */
    @JsonValue
    public String apiValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
