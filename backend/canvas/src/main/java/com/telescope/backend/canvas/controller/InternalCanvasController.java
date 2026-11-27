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

/**
 * 다른 서비스(work의 문서)가 캔버스 권한을 확인하는 내부 API. 내부 키로만 부를 수 있다(InternalApiKeyFilter).
 * 캔버스가 없거나 지워졌으면 {@code C002}(404)다. 허용·거절 판단은 부른 쪽이 지금 규칙대로 한다.
 */
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
