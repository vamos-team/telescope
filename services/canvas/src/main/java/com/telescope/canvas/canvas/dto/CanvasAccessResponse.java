package com.telescope.canvas.canvas.dto;

import com.telescope.canvas.canvas.entity.CanvasRole;

public record CanvasAccessResponse(boolean active, CanvasRole role) {
}
