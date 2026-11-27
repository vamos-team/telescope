package com.telescope.backend.canvas.dto;

import com.telescope.backend.canvas.entity.CanvasRole;

/** 내부 권한 확인 응답. 권한이 없으면 {@code role}이 비어 있다. */
public record CanvasAccessResponse(boolean active, CanvasRole role) {
}
