package com.telescope.backend.canvas.dto;

import java.util.UUID;

/**
 * 글 충돌(409 K003) 응답의 data. 화면은 이 최신 글을 보여 주고 다시 고치게 한다.
 *
 * @param targetId 충돌한 카드 또는 연결 ID
 * @param field    `text`(메모 카드 본문) 또는 `memo`(카드 메모·선 메모)
 * @param text     지금 저장된 글(비었으면 null)
 * @param version  지금 저장된 버전
 */
public record TextConflict(UUID targetId, String field, String text, int version) {
}
