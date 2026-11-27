package com.telescope.canvas.canvas.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 묶음 요청. 이벤트 종류와 필드는 `docs/frontend/api-spec.md` 3.6, 저장 규칙은 `docs/backend/api-spec.md` 5장.
 *
 * @param requestId 묶음마다 화면이 만드는 UUID. 같은 묶음을 다시 보낼 때는 같은 값
 * @param events    이벤트 목록. 종류마다 필드가 달라 Map으로 받고 서비스에서 확인한다
 */
public record CanvasEventsRequest(
        @NotNull UUID requestId,
        @NotNull @Size(min = 1, max = 500) List<Map<String, Object>> events
) {
}
