package com.telescope.research.document.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 문서 하나(프론트 명세의 {@code Doc}). 문서 카드와 근거 사이드바가 쓴다.
 *
 * @param id        문서 ID
 * @param kind      종류. 사용자 업로드는 {@code internal}
 * @param company   회사
 * @param title     제목
 * @param publisher 출처
 * @param date      발행일
 * @param summary   요약. 자료 준비가 끝나기 전이거나 ai 결과에 없으면 비어 있다
 * @param figures   수치 목록({@code label, value, unit, period}). 없으면 빈 목록
 * @param tags      개념 태그 목록. 없으면 빈 목록
 * @param url       원문 주소. 사용자 업로드는 빈 값
 * @param uploaded  사용자가 올린 문서인지
 */
public record DocumentResponse(
        Long id,
        String kind,
        String company,
        String title,
        String publisher,
        LocalDate date,
        String summary,
        List<Object> figures,
        List<Object> tags,
        String url,
        boolean uploaded
) {
}
