package com.telescope.canvas.canvas.dto;

import com.telescope.canvas.canvas.entity.CanvasRole;

import java.time.LocalDateTime;

/**
 * 캔버스 목록의 한 항목이자 캔버스 열기 응답의 meta.
 *
 * @param myRole    요청한 사람의 권한(owner / edit / view)
 * @param updatedAt 마지막으로 고친 시각. 아직 고친 적 없으면 만든 시각
 * @param updatedBy 마지막으로 고친 사람. 아직 고친 적 없으면 소유자
 * @param cardCount 카드 수
 */
public record CanvasSummaryResponse(
        Long id,
        String name,
        String quarter,
        UserRef owner,
        CanvasRole myRole,
        LocalDateTime updatedAt,
        UserRef updatedBy,
        long cardCount
) {
}
