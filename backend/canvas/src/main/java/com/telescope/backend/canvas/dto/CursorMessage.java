package com.telescope.backend.canvas.dto;

/**
 * 다른 사람에게 보내는 커서 위치(`/topic/canvases/{id}/cursors`). 저장하지 않는다.
 * 보낸 사람에게도 가므로 화면은 `user.id`가 자기면 무시한다.
 */
public record CursorMessage(UserRef user, double x, double y) {
}
