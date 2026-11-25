package com.telescope.backend.canvas.dto;

import java.util.Map;

/**
 * 이벤트 묶음 처리 결과. 같은 requestId로 다시 보내면 처음 응답을 그대로 돌려준다.
 *
 * @param lastSeq           반영 후 캔버스의 마지막 이벤트 번호
 * @param firstChangedLayer 처음 바뀐 컨텍스트 층(`L1_sources` / `L2_workspace` / `L3_edits`). 없으면 null
 * @param textVersions      이번 묶음으로 바뀐 글 버전. 키는 카드·연결 ID
 */
public record CanvasEventsResponse(long lastSeq, String firstChangedLayer, Map<String, TextVersions> textVersions) {
}
