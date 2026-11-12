package com.telescope.backend.infra.python;

/**
 * ai 서비스 작업의 상태 조회 응답.
 *
 * <p>TODO(가칭): {@code result}는 작업 종류(자료 준비/분석/예상 질문)마다 모양이 다를 것으로
 * 예상되지만, 아직 ai 쪽 스키마가 없어 {@code Object}로 느슨하게 둔다.
 * 실제 계약이 정해지면 작업 종류별 result record로 쪼개고, 이 타입은 sealed interface 등으로
 * 바꾸는 걸 검토한다.
 *
 * @param jobId 작업 식별자
 * @param status 현재 상태
 * @param result 성공 시 결과. 완료되지 않았으면 {@code null}
 * @param errorMessage 실패 시 사유. 실패가 아니면 {@code null}
 */
public record JobStatusResponse(
        String jobId,
        JobStatus status,
        Object result,
        String errorMessage
) {
}
