package com.telescope.backend.canvas.dto;

import com.telescope.backend.canvas.entity.CanvasRole;
import jakarta.validation.constraints.NotNull;

/**
 * 공유 명단의 한 사람.
 *
 * @param role edit 또는 view
 */
public record ShareMember(@NotNull Long userId, @NotNull CanvasRole role) {
}
