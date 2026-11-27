package com.telescope.analysis.document.dto;

import java.util.List;

/**
 * 업로드 응답({@code 202}). 화면은 {@code GET /jobs/{jobId}}를 폴링하고, {@code SUCCEEDED}가 되면
 * {@code GET /documents/{id}}로 카드 데이터를 받는다.
 *
 * @param jobId       자료 준비 작업 ID(backend {@code Job.id})
 * @param documentIds 만든 문서 ID. 요청당 파일 하나라 하나다(프론트 명세 모양을 유지하려고 목록으로 둔다)
 */
public record DocumentUploadResponse(Long jobId, List<Long> documentIds) {
}
