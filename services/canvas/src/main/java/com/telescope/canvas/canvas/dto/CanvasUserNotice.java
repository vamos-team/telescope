package com.telescope.canvas.canvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.telescope.canvas.canvas.entity.CanvasRole;

/**
 * 나에게만 오는 캔버스 알림(`/user/queue/canvases`).
 *
 * @param type `revoked`(공유에서 빠짐) / `deleted`(캔버스 삭제) / `role_changed`(권한 바뀜)
 * @param role `role_changed`일 때 새 권한
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanvasUserNotice(String type, Long canvasId, CanvasRole role) {
}
