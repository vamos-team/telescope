package com.telescope.backend.canvas.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 공유 명단 전체 교체 요청(소유자만). 목록에서 빠진 사람은 권한이 사라진다. */
public record CanvasSharesUpdateRequest(@NotNull List<@Valid ShareMember> members) {
}
