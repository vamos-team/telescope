package com.telescope.canvas.canvas.repository;

import com.telescope.canvas.canvas.entity.CanvasCard;
import com.telescope.canvas.canvas.entity.CardKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CanvasCardRepository extends JpaRepository<CanvasCard, UUID> {

    /**
     * 이 캔버스의 카드 하나. 화면이 보낸 카드 ID가 다른 캔버스의 카드를 가리켜도 찾지 못하게
     * 항상 캔버스 ID와 함께 찾는다.
     */
    Optional<CanvasCard> findByCanvasIdAndId(Long canvasId, UUID id);

    /** 이 캔버스의 카드 전체(만든 순). */
    List<CanvasCard> findByCanvasIdOrderByCreatedAtAsc(Long canvasId);

    /** 이 캔버스에서 한 종류의 카드 전체. 카드를 지울 때 비교 카드의 원본 목록을 고치는 데 쓴다. */
    List<CanvasCard> findByCanvasIdAndKind(Long canvasId, CardKind kind);

    /** 캔버스별 카드 수. 목록 화면에서 한 번에 센다. 결과는 [캔버스 ID, 개수]. */
    @Query("select c.canvasId, count(c) from CanvasCard c where c.canvasId in :canvasIds group by c.canvasId")
    List<Object[]> countByCanvasIds(@Param("canvasIds") Collection<Long> canvasIds);
}
