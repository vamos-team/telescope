package com.telescope.integration.bridge;

import com.telescope.canvas.canvas.service.CanvasAccessService;
import com.telescope.analysis.document.service.CanvasAccessClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 통합 테스트에서 canvas를 같은 프로세스로 띄울 때 쓰는 캔버스 권한 확인. 운영은 HttpCanvasAccessClient가 한다. */
@Primary
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
