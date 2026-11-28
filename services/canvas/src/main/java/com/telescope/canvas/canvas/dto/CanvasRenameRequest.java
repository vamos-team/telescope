package com.telescope.canvas.canvas.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 캔버스 이름 변경 요청. 빈 문자열이면 "제목 없는 캔버스". */
public record CanvasRenameRequest(@NotNull @Size(max = 200) String name) {
}
