package com.telescope.backend.canvas.dto;

/**
 * 접속자 들어옴·나감(`/topic/canvases/{id}/presence`).
 *
 * @param type `join` 또는 `leave`
 */
public record PresenceMessage(String type, UserRef user) {
}
