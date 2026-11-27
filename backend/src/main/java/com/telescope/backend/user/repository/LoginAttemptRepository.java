package com.telescope.backend.user.repository;

import com.telescope.backend.user.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, String> {

    /**
     * 실패를 한 번 더 센다. 행이 없으면 만들고, 있으면 그 자리에서 올린다(PostgreSQL {@code ON CONFLICT}).
     * 스키마는 {@code {h-schema}}로 붙인다(네이티브 쿼리에는 {@code hibernate.default_schema}가 자동으로 붙지 않는다).
     * 처음 실패하는 사번은 잠글 행이 아직 없어 행 잠금만으로는 동시 실패를 셀 수 없으므로 한 문장으로 처리한다.
     *
     * <ul>
     *   <li>잠금 시각이 지난 행은 1부터 다시 센다</li>
     *   <li>센 횟수가 {@code maxFailures} 이상이면 {@code lockUntil}까지 잠근다. 이미 잠겨 있으면 잠금 시각을 늘리지 않는다</li>
     * </ul>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            INSERT INTO {h-schema}login_attempt AS la (employee_no, failed_count, locked_until, created_at, updated_at)
            VALUES (:employeeNo, 1, CASE WHEN 1 >= :maxFailures THEN CAST(:lockUntil AS TIMESTAMP) END, :now, :now)
            ON CONFLICT (employee_no) DO UPDATE SET
                failed_count = CASE
                    WHEN la.locked_until IS NOT NULL AND la.locked_until <= :now THEN 1
                    ELSE la.failed_count + 1 END,
                locked_until = CASE
                    WHEN (CASE
                            WHEN la.locked_until IS NOT NULL AND la.locked_until <= :now THEN 1
                            ELSE la.failed_count + 1 END) < :maxFailures THEN NULL
                    WHEN la.locked_until IS NOT NULL AND la.locked_until > :now
                        THEN la.locked_until
                    ELSE CAST(:lockUntil AS TIMESTAMP) END,
                updated_at = :now
            """)
    int recordFailure(@Param("employeeNo") String employeeNo, @Param("maxFailures") int maxFailures,
                      @Param("lockUntil") LocalDateTime lockUntil, @Param("now") LocalDateTime now);

    /** 로그인에 성공하면 횟수와 잠금을 지운다. 이미 0이면 쓰지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE {h-schema}login_attempt SET failed_count = 0, locked_until = NULL, updated_at = :now
            WHERE employee_no = :employeeNo AND (failed_count > 0 OR locked_until IS NOT NULL)
            """)
    int reset(@Param("employeeNo") String employeeNo, @Param("now") LocalDateTime now);
}
