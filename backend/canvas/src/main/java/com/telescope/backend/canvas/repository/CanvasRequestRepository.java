package com.telescope.backend.canvas.repository;

import com.telescope.backend.canvas.entity.CanvasRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CanvasRequestRepository extends JpaRepository<CanvasRequest, Long> {

    /** 이미 처리한 묶음인지 찾는다. 있으면 저장해 둔 응답을 그대로 돌려준다. */
    Optional<CanvasRequest> findByCanvasIdAndRequestId(Long canvasId, UUID requestId);
}
