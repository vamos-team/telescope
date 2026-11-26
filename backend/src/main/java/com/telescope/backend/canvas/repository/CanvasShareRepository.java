package com.telescope.backend.canvas.repository;

import com.telescope.backend.canvas.entity.CanvasShare;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CanvasShareRepository extends JpaRepository<CanvasShare, Long> {

    /** 한 사람의 이 캔버스 초대 권한. 초대받지 않았으면 비어 있다. */
    Optional<CanvasShare> findByCanvasIdAndUserId(Long canvasId, Long userId);

    /** 이 캔버스의 공유 명단 전체. */
    List<CanvasShare> findByCanvasIdOrderByIdAsc(Long canvasId);

    /** 여러 캔버스에서 이 사람이 초대받은 권한. 목록 화면에서 내 권한을 한 번에 구한다. */
    List<CanvasShare> findByUserIdAndCanvasIdIn(Long userId, Collection<Long> canvasIds);
}
