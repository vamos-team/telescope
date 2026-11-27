package com.telescope.backend.canvas.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 다른 사람에게 보내는 커밋된 이벤트 묶음(`/topic/canvases/{id}/events`).
 * 받는 쪽은 `firstSeq`가 자기 마지막 번호 + 1인지 확인하고, 아니면 캔버스를 다시 조회한다.
 * 보낸 사람도 받으며, 자기 `requestId`면 이미 반영했으므로 번호만 갱신한다.
 */
public record CanvasEventsMessage(
        long firstSeq,
        long lastSeq,
        UUID requestId,
        UserRef author,
        List<Map<String, Object>> events,
        Map<String, TextVersions> textVersions
) {
}
