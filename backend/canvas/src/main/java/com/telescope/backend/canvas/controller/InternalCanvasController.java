package com.telescope.backend.canvas.controller;

import com.telescope.backend.canvas.dto.CanvasAccessResponse;
import com.telescope.backend.canvas.entity.Canvas;
import com.telescope.backend.canvas.service.CanvasAccessService;
import com.telescope.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/canvases")
@RequiredArgsConstructor
public class InternalCanvasController {

    private final CanvasAccessService canvasAccess;

    @GetMapping("/{canvasId}/access")
    public ApiResponse<CanvasAccessResponse> access(@PathVariable Long canvasId, @RequestParam Long userId) {
        Canvas canvas = canvasAccess.loadActive(canvasId);
        return ApiResponse.success(new CanvasAccessResponse(true, canvasAccess.roleOf(canvas, userId).orElse(null)));
    }
}
