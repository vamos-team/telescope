package com.telescope.backend.canvas.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 활동 로그 한 줄. 이벤트 기록에서 만든다.
 *
 * @param cardId 관련 카드 ID. 카드와 무관한 활동(공유 변경 등)이면 null
 */
public record CanvasActivityResponse(UserRef author, String text, UUID cardId, LocalDateTime createdAt) {
}
