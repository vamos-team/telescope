package com.telescope.backend.canvas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 새 캔버스 요청.
 *
 * @param name      이름. 비면 "제목 없는 캔버스"
 * @param quarter   분기 (예: "2026년 1분기")
 * @param editorIds 편집 권한으로 바로 초대할 사용자 ID. 없으면 소유자만
 */
public record CanvasCreateRequest(
        @Size(max = 200) String name,
        @NotBlank @Size(max = 20) String quarter,
        List<Long> editorIds
) {
}
