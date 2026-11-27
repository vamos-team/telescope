package com.telescope.canvas.canvas.dto;

import java.util.List;
import java.util.Map;

/**
 * 캔버스 열기 응답. 카드는 종류마다 필드가 달라 Map(공통 필드 + 종류별 필드)으로 담는다.
 *
 * @param lastSeq  마지막 이벤트 번호. 실시간 변경을 이어 붙일 기준
 * @param warnings 일부를 복원하지 못한 이유. 그래도 나머지는 정상으로 돌려준다
 */
public record CanvasDetailResponse(
        CanvasSummaryResponse meta,
        List<Map<String, Object>> cards,
        List<CanvasLinkResponse> links,
        long lastSeq,
        List<String> warnings
) {
}
