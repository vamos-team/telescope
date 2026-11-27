package com.telescope.canvas.canvas.repository;

import com.telescope.canvas.CanvasApplication;
import org.springframework.test.context.ContextConfiguration;
import com.telescope.common.test.TestcontainersConfig;
import com.telescope.canvas.canvas.entity.Canvas;
import com.telescope.canvas.canvas.entity.CanvasCard;
import com.telescope.canvas.canvas.entity.CanvasEvent;
import com.telescope.canvas.canvas.entity.CanvasLink;
import com.telescope.canvas.canvas.entity.CanvasRole;
import com.telescope.canvas.canvas.entity.CanvasShare;
import com.telescope.canvas.canvas.entity.CardKind;
import com.telescope.common.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V9 마이그레이션이 적용된 PostgreSQL에서 {@code ddl-auto: validate}를 통과하는지,
 * JSONB 컬럼(문자열 매핑)이 그대로 저장·조회되는지, 목록 조회가 소유·초대를 구분하는지 확인한다.
 */
@DataJpaTest
@ContextConfiguration(classes = CanvasApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class})
class CanvasRepositoryTest {

    @Autowired CanvasRepository canvasRepository;
    @Autowired CanvasShareRepository shareRepository;
    @Autowired CanvasCardRepository cardRepository;
    @Autowired CanvasLinkRepository linkRepository;
    @Autowired CanvasEventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;

    Long owner;
    Long guest;
    Long stranger;

    @BeforeEach
    void setUp() {
        owner = insertUser("1001");
        guest = insertUser("1002");
        stranger = insertUser("1003");
    }

    @Test
    void 카드의_JSONB_필드와_글이_그대로_저장되고_조회된다() {
        Canvas canvas = canvasRepository.save(Canvas.create("예산 검토", "2026년 1분기", owner));
        UUID cardId = UUID.randomUUID();
        CanvasCard card = CanvasCard.create(cardId, canvas.getId(), CardKind.TABLE, 10, 20,
                "{\"tableId\":\"SKT_2026Q1_is\",\"rows\":[3,5]}", owner);
        card.changeMemo("감가상각비 추이 확인", owner);
        cardRepository.save(card);
        em.flush();
        em.clear();

        CanvasCard found = cardRepository.findByCanvasIdAndId(canvas.getId(), cardId).orElseThrow();
        // PostgreSQL은 JSONB를 정규화해서 돌려주므로(공백·키 순서) 값만 확인한다
        assertThat(found.getProps()).contains("\"tableId\": \"SKT_2026Q1_is\"").contains("\"rows\": [3, 5]");
        assertThat(found.getMemo()).isEqualTo("감가상각비 추이 확인");
        assertThat(found.getMemoBy()).isEqualTo(owner);
        assertThat(found.getMemoVersion()).isEqualTo(1);
        assertThat(found.isNew()).isFalse();
    }

    // 카드 삭제 시 연결을 지우는 건 마이그레이션의 ON DELETE CASCADE FK가 한다(엔티티로는 표현 못 함).
    // create-drop 스키마에는 없으므로 이 검증에만 그 FK를 만들어 둔다.
    @Sql(statements = {
            "ALTER TABLE canvas_link DROP CONSTRAINT IF EXISTS fk_canvas_link_source",
            "ALTER TABLE canvas_link ADD CONSTRAINT fk_canvas_link_source FOREIGN KEY (source_card_id) REFERENCES canvas_card(id) ON DELETE CASCADE",
            "ALTER TABLE canvas_link DROP CONSTRAINT IF EXISTS fk_canvas_link_target",
            "ALTER TABLE canvas_link ADD CONSTRAINT fk_canvas_link_target FOREIGN KEY (target_card_id) REFERENCES canvas_card(id) ON DELETE CASCADE"
    })
    @Test
    void 카드를_지우면_닿는_연결도_DB가_지운다() {
        Canvas canvas = canvasRepository.save(Canvas.create("c", "2026년 1분기", owner));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), link = UUID.randomUUID();
        cardRepository.save(CanvasCard.create(a, canvas.getId(), CardKind.DOC, 0, 0, "{}", owner));
        cardRepository.save(CanvasCard.create(b, canvas.getId(), CardKind.DOC, 0, 0, "{}", owner));
        linkRepository.save(CanvasLink.create(link, canvas.getId(), a, b, owner));
        em.flush();

        jdbc.update("delete from canvas_card where id = ?", a);
        em.clear();

        assertThat(linkRepository.findById(link)).isEmpty();
    }

    @Test
    void 목록은_소유와_초대를_구분하고_삭제된_캔버스는_빠진다() {
        canvasRepository.save(Canvas.create("내 것", "2026년 1분기", owner));
        Canvas shared = canvasRepository.save(Canvas.create("공유받음", "2026년 1분기", stranger));
        Canvas deleted = canvasRepository.save(Canvas.create("지움", "2026년 1분기", owner));
        shareRepository.save(CanvasShare.of(shared.getId(), owner, CanvasRole.VIEW));
        deleted.delete(java.time.LocalDateTime.now());
        em.flush();

        assertThat(canvasRepository.findVisible(owner)).extracting(Canvas::getName)
                .containsExactlyInAnyOrder("내 것", "공유받음");
        assertThat(canvasRepository.findOwned(owner)).extracting(Canvas::getName).containsExactly("내 것");
        assertThat(canvasRepository.findShared(owner)).extracting(Canvas::getName).containsExactly("공유받음");
        assertThat(canvasRepository.findVisible(guest)).isEmpty();
    }

    @Test
    void 이벤트는_캔버스와_번호가_겹치면_저장되지_않는다() {
        Canvas canvas = canvasRepository.save(Canvas.create("c", "2026년 1분기", owner));
        UUID req = UUID.randomUUID();
        eventRepository.save(CanvasEvent.of(canvas.getId(), 1, req, owner, "move_card", "{}"));
        em.flush();

        List<CanvasEvent> events = eventRepository.findByCanvasIdOrderBySeqAsc(canvas.getId());
        assertThat(events).extracting(CanvasEvent::getSeq).containsExactly(1L);

        // 같은 세션으로 다시 flush하면 세션이 깨지므로 중복 삽입은 JDBC로 확인한다.
        // PostgreSQL은 오류 뒤 같은 트랜잭션의 쿼리를 거부하므로 이 확인을 마지막에 둔다
        assertThatThrownBy(() -> jdbc.update(
                "insert into canvas_event (canvas_id, seq, request_id, author_id, type, payload) "
                        + "values (?, 1, ?, ?, 'move_card', '{}'::jsonb)",
                canvas.getId(), UUID.randomUUID(), owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static final java.util.concurrent.atomic.AtomicLong USER_IDS = new java.util.concurrent.atomic.AtomicLong(1000);

    // 캔버스는 사용자 id만 Long으로 들고 FK가 없다. 테스트는 행을 만들지 않고 id만 쓴다.
    private Long insertUser(String employeeNo) {
        return USER_IDS.incrementAndGet();
    }
}
