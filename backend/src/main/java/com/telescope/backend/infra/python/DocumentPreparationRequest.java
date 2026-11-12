package com.telescope.backend.infra.python;

import jakarta.validation.constraints.NotBlank;

/**
 * ai 서비스에 자료 준비(ingest)를 요청하는 계약.
 *
 * <p>TODO(가칭): {@code document} 패키지(이슈 #10 시점 기준 미구현)의 실제 식별자 타입이
 * 정해지면 {@code documentId}/{@code documentVersionId} 타입(Long vs UUID 등)을 맞춘다.
 * 필드 구성 전체가 ai 담당자와 조율 전 초안이다.
 *
 * @param documentId 준비 대상 문서 식별자
 * @param documentVersionId 준비 대상 문서 버전 식별자
 * @param fileStorageKey {@link com.telescope.backend.infra.storage.FileStorage#store}가 반환한 원본 파일 키
 */
public record DocumentPreparationRequest(
        @NotBlank String documentId,
        @NotBlank String documentVersionId,
        @NotBlank String fileStorageKey
) {
}
