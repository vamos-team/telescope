package com.telescope.analysis.infra.python;

/**
 * ai 서비스에 해당 jobId의 작업이 없을 때 던진다.
 * 네트워크 오류 같은 일시적 실패와 구분하기 위한 전용 예외다.
 */
public class AiJobNotFoundException extends RuntimeException {

    public AiJobNotFoundException(String jobId) {
        super("ai 서비스에 없는 작업: " + jobId);
    }
}
