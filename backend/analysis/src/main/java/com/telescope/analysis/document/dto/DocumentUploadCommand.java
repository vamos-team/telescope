package com.telescope.analysis.document.dto;

import java.time.LocalDate;

/**
 * 업로드 요청에서 파일 내용을 뺀 값. 파일 내용은 서비스에 스트림으로 따로 넘긴다.
 *
 * @param canvasId         문서가 속할 캔버스
 * @param company          회사(화면의 현재 회사)
 * @param publishedDate    발행일. 비어 있으면 올린 날
 * @param originalFilename 원본 파일명. 확장자 확인과 제목에 쓴다
 * @param size             파일 크기(바이트)
 */
public record DocumentUploadCommand(
        Long canvasId,
        String company,
        LocalDate publishedDate,
        String originalFilename,
        long size
) {
}
