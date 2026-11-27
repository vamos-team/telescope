package com.telescope.canvas.canvas.dto;

import java.util.UUID;

/** 캔버스 열기 응답의 연결 하나. */
public record CanvasLinkResponse(
        UUID id,
        UUID source,
        UUID target,
        String memo,
        int memoVersion,
        UserRef memoBy
) {
}
