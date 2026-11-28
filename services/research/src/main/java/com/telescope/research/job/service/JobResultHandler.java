package com.telescope.research.job.service;

import com.telescope.research.job.entity.JobType;

/**
 * 끝난 작업의 결과를 대상 도메인(자료 준비, 분석, 예측)에 저장하는 연결 지점.
 *
 * <p>대상 도메인이 자기 작업 종류에 맞게 구현해 빈으로 등록한다. job 도메인은 대상 도메인을 직접 알지 않고
 * 이 인터페이스로만 부른다. 작업 종류마다 처리기는 하나만 둔다(둘이면 기동 시점에 실패한다).
 *
 * <p>{@link JobService}는 ai가 {@code SUCCEEDED}를 알려 줄 때 작업 행을 잠근 트랜잭션 안에서 이 처리기를 부르고,
 * 같은 트랜잭션에서 작업을 완료로 확정한다. 그래서 구현은 다음을 지켜야 한다.
 * <ul>
 *   <li>DB 저장만 한다. 외부 호출(ai 서비스, 파일 저장소)을 하지 않는다. 트랜잭션과 행 잠금이 길어진다.</li>
 *   <li>저장하지 못하면 예외를 던진다. 저장과 완료 확정이 함께 되돌려지고, 다음 조회에서 다시 시도한다.</li>
 *   <li>같은 작업의 결과가 두 번 들어오지 않는다고 기대해도 된다(행 잠금 후 이미 끝났으면 부르지 않는다).</li>
 * </ul>
 */
public interface JobResultHandler {

    /** 이 처리기가 맡는 작업 종류. */
    JobType jobType();

    /**
     * 작업 결과를 대상 도메인에 저장한다.
     *
     * @param jobId  backend의 작업 ID. 대상(예: {@code DocumentVersion.jobId})을 찾는 데 쓴다
     * @param result ai가 돌려준 결과(JSON을 읽은 값). 모양은 작업 종류마다 다르며 비어 있을 수 있다
     * @throws RuntimeException 저장하지 못했을 때. 작업은 끝나지 않고 다시 시도된다
     */
    void saveResult(Long jobId, Object result);
}
