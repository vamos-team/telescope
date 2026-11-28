package com.telescope.canvas.canvas.service;

import com.telescope.canvas.canvas.dto.TextVersions;
import com.telescope.canvas.canvas.dto.UserRef;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 묶음이 저장됐다는 알림. 같은 트랜잭션 안에서 발행되며, 실시간 전달(#83)은
 * {@code @TransactionalEventListener(AFTER_COMMIT)}으로 받아 커밋된 변경만 다른 사람에게 보낸다.
 *
 * @param author       보낸 사람. 이름은 트랜잭션 안에서 읽어 둔다(커밋 뒤 리스너가 DB를 다시 보지 않게, #149)
 * @param events       저장된 이벤트(요청에서 받은 필드 그대로, 이동은 카드별 마지막 위치만)
 * @param textVersions 이번 묶음으로 바뀐 글 버전. 키는 카드·연결 ID
 */
public record CanvasEventsCommitted(
        Long canvasId,
        long firstSeq,
        long lastSeq,
        UUID requestId,
        UserRef author,
        List<Map<String, Object>> events,
        Map<String, TextVersions> textVersions
) {
}
