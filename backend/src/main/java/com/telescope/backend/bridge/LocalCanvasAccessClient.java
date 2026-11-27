package com.telescope.backend.bridge;

import com.telescope.backend.canvas.service.CanvasAccessService;
import com.telescope.backend.document.service.CanvasAccessClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalCanvasAccessClient implements CanvasAccessClient {

    private final CanvasAccessService canvasAccess;

    @Override
    public void requireView(Long canvasId, Long userId) {
        canvasAccess.requireView(canvasAccess.loadActive(canvasId), userId);
    }

    @Override
    public void requireEdit(Long canvasId, Long userId) {
        canvasAccess.requireEdit(canvasAccess.loadActive(canvasId), userId);
    }
}
