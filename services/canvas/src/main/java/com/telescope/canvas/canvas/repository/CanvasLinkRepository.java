package com.telescope.canvas.canvas.repository;

import com.telescope.canvas.canvas.entity.CanvasLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CanvasLinkRepository extends JpaRepository<CanvasLink, UUID> {

    /** 이 캔버스의 연결 하나. 항상 캔버스 ID와 함께 찾는다. */
    Optional<CanvasLink> findByCanvasIdAndId(Long canvasId, UUID id);

    /** 이 캔버스의 연결 전체(만든 순). */
    List<CanvasLink> findByCanvasIdOrderByCreatedAtAsc(Long canvasId);
}
