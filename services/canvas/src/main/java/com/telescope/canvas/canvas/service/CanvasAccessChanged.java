package com.telescope.canvas.canvas.service;

import com.telescope.canvas.canvas.entity.CanvasRole;

import java.util.Map;
import java.util.Set;

/**
 * 캔버스 권한이 바뀌었다는 알림(공유 변경, 캔버스 삭제). 커밋 뒤 실시간 전달(#83)이 받아
 * 권한이 사라진 사람의 구독을 끊고 개인 알림을 보낸다.
 *
 * @param revokedUserIds 권한이 사라진 사람. 삭제면 소유자를 뺀 모든 권한자
 * @param changedRoles   권한이 새로 생기거나 바뀐 사람과 새 권한
 * @param deleted        캔버스가 삭제됐는지
 */
public record CanvasAccessChanged(
        Long canvasId,
        Set<Long> revokedUserIds,
        Map<Long, CanvasRole> changedRoles,
        boolean deleted
) {
}
