package com.telescope.analysis.infra.python;

/**
 * ai 서비스(Python)가 보고하는 작업 상태.
 *
 * <p>DB에 저장하는 상태는 {@code job} 패키지의 별도 enum이다. ai 계약은 아직 초안이라
 * 값이 바뀔 수 있어서, 저장 값과 분리하고 {@code JobService}에서 변환한다.
 */
public enum AiJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
