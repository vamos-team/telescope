package com.telescope.backend.canvas.dto;

import java.util.UUID;

/** 캔버스 열기 응답의 연결 하나. */
public record CanvasLinkResponse(
        UUID id,
        UUID source,
        UUID target,
        String rel,
        String prompt,
        int promptVersion
) {
}
