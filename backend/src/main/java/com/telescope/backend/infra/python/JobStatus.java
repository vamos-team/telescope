package com.telescope.backend.infra.python;

/**
 * ai 서비스(Python)에서 실행 중인 작업의 상태.
 *
 * <p>TODO: {@code job} 패키지(이슈 #7 기준 비동기 작업 공통 상태 전이 담당)가 구현되면,
 * 거기서 쓰는 상태 enum과 이름/전이 규칙이 겹칠 수 있다. 그때 하나로 합칠지,
 * 이 enum은 "ai 서비스가 보고하는 상태"로 분리해서 유지할지 결정한다.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED
}
