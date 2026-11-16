package com.telescope.backend.job.repository;

import com.telescope.backend.job.entity.Job;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
