package com.telescope.backend.canvas.dto;

import com.telescope.backend.canvas.entity.CanvasRole;

public record CanvasAccessResponse(boolean active, CanvasRole role) {
}
