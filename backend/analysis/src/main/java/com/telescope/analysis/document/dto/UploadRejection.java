package com.telescope.analysis.document.dto;

/**
 * 업로드 거절({@code 400 D001}) 응답의 {@code data}. 화면은 {@code reason}으로 거절 이유를 나눈다.
 *
 * @param reason {@link #FORMAT_MISMATCH} 또는 {@link #COMPANY_MISMATCH}
 */
public record UploadRejection(String reason) {

    /** 허용하지 않는 형식, 확장자 없음, 빈 파일. */
    public static final String FORMAT_MISMATCH = "FORMAT_MISMATCH";
    /** 업로드 대상 회사 목록에 없는 회사. */
    public static final String COMPANY_MISMATCH = "COMPANY_MISMATCH";

    /** 형식 불일치. */
    public static UploadRejection format() {
        return new UploadRejection(FORMAT_MISMATCH);
    }

    /** 회사 불일치. */
    public static UploadRejection company() {
        return new UploadRejection(COMPANY_MISMATCH);
    }
}
