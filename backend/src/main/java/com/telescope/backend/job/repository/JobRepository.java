package com.telescope.backend.job.repository;

import com.telescope.backend.job.entity.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.telescope.backend.job.entity.JobStatus;
import com.telescope.backend.job.entity.JobType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobRepository extends JpaRepository<Job, Long> {

    /**
     * 행을 잠그고 읽는다. 트랜잭션 안에서 호출해야 한다.
     * 같은 작업을 동시에 갱신하는 요청을 한 줄로 세워, 뒤에 온 요청이 앞선 요청이
     * 커밋한 최신 상태를 보고 판단하게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from Job j where j.id = :id")
    Optional<Job> findByIdForUpdate(@Param("id") Long id);

    /** 이 종류의 아직 끝나지 않은(QUEUED·RUNNING) 작업 ID. 화면이 폴링하지 않는 배치 작업을 서버가 확인할 때 쓴다. */
    @Query("select j.id from Job j where j.jobType = :type and j.status in :statuses order by j.id")
    List<Long> findIdsByJobTypeAndStatusIn(@Param("type") JobType type, @Param("statuses") Collection<JobStatus> statuses);

    /** 여러 작업을 한 번에 읽는다(목록 화면용, ai를 부르지 않음). */
    List<Job> findByIdIn(Collection<Long> ids);
}
