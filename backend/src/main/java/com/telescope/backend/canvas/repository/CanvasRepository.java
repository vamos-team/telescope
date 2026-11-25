package com.telescope.backend.canvas.repository;

import com.telescope.backend.canvas.entity.Canvas;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CanvasRepository extends JpaRepository<Canvas, Long> {

    /**
     * 캔버스 행을 잠그고 읽는다. 트랜잭션 안에서 호출해야 한다.
     * 같은 캔버스의 이벤트 저장·공유 변경을 한 줄로 세워 이벤트 번호가 겹치지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Canvas c where c.id = :id")
    Optional<Canvas> findByIdForUpdate(@Param("id") Long id);

    /** 내가 소유했거나 초대받은, 삭제되지 않은 캔버스를 최근 수정 순으로 찾는다. */
    @Query("""
            select c from Canvas c
            where c.deletedAt is null
              and (c.ownerId = :userId
                   or c.id in (select s.canvasId from CanvasShare s where s.userId = :userId))
            order by coalesce(c.lastEditedAt, c.createdAt) desc, c.id desc
            """)
    List<Canvas> findVisible(@Param("userId") Long userId);

    /** 내가 소유한, 삭제되지 않은 캔버스를 최근 수정 순으로 찾는다. */
    @Query("""
            select c from Canvas c
            where c.deletedAt is null and c.ownerId = :userId
            order by coalesce(c.lastEditedAt, c.createdAt) desc, c.id desc
            """)
    List<Canvas> findOwned(@Param("userId") Long userId);

    /** 내가 초대받은(소유하지 않은), 삭제되지 않은 캔버스를 최근 수정 순으로 찾는다. */
    @Query("""
            select c from Canvas c
            where c.deletedAt is null
              and c.id in (select s.canvasId from CanvasShare s where s.userId = :userId)
            order by coalesce(c.lastEditedAt, c.createdAt) desc, c.id desc
            """)
    List<Canvas> findShared(@Param("userId") Long userId);
}
