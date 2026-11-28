package com.telescope.canvas.canvas.repository;

import com.telescope.canvas.canvas.entity.CanvasMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CanvasMappingRepository extends JpaRepository<CanvasMapping, Long> {

    /** (캔버스, 개념, 회사)의 대응 결정. 없으면 비어 있다. */
    Optional<CanvasMapping> findByCanvasIdAndConceptIdAndCompany(Long canvasId, String conceptId, String company);

    /** 이 캔버스의 대응 결정 전체. 비교를 ai에 요청할 때 넘긴다. */
    List<CanvasMapping> findByCanvasId(Long canvasId);
}
