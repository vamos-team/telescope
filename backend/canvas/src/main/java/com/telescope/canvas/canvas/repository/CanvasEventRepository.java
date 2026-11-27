package com.telescope.canvas.canvas.repository;

import com.telescope.canvas.canvas.entity.CanvasEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CanvasEventRepository extends JpaRepository<CanvasEvent, Long> {

    /** 활동 로그용: 주어진 종류의 이벤트를 최근 순으로. 개수는 {@code pageable}로 자른다. */
    List<CanvasEvent> findByCanvasIdAndTypeInOrderBySeqDesc(Long canvasId, Collection<String> types, Pageable pageable);

    /** 이 캔버스의 이벤트 전체(번호 순). 테스트와 복구 확인에 쓴다. */
    List<CanvasEvent> findByCanvasIdOrderBySeqAsc(Long canvasId);
}
