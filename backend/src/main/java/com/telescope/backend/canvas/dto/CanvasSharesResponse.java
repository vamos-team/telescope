package com.telescope.backend.canvas.dto;

import java.util.List;

/** 공유 명단. 소유자는 `ownerId`로만 나오고 `members`에는 없다. */
public record CanvasSharesResponse(Long ownerId, List<ShareMember> members) {
}
