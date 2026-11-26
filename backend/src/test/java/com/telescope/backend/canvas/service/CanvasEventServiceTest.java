package com.telescope.backend.canvas.service;

import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.canvas.dto.CanvasCreateRequest;
import com.telescope.backend.canvas.dto.CanvasDetailResponse;
import com.telescope.backend.canvas.dto.CanvasEventsRequest;
import com.telescope.backend.canvas.dto.CanvasEventsResponse;
import com.telescope.backend.canvas.dto.ShareMember;
import com.telescope.backend.canvas.dto.TextConflict;
import com.telescope.backend.canvas.entity.CanvasEvent;
import com.telescope.backend.canvas.entity.CanvasRole;
import com.telescope.backend.canvas.repository.CanvasEventRepository;
import com.telescope.backend.global.config.JpaAuditingConfig;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.telescope.backend.canvas.service.CanvasTestSupport.batch;
import static com.telescope.backend.canvas.service.CanvasTestSupport.event;
import static com.telescope.backend.canvas.service.CanvasTestSupport.ids;
import static com.telescope.backend.canvas.service.CanvasTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이벤트 묶음 저장 규칙을 실제로 커밋되는 상태에서 확인한다(테스트 전체 트랜잭션 끔).
 * 규칙: {@code docs/backend/api-spec.md} 5장, 결정 이유: {@code docs/backend/canvas.md}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class, CanvasService.class, CanvasEventService.class,
        CanvasAccessService.class, CanvasEventServiceTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
class CanvasEventServiceTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired CanvasService canvasService;
    @Autowired CanvasEventService eventService;
    @Autowired CanvasEventRepository eventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents published;

    Long owner;
    Long editor;
    Long viewer;
    Long stranger;
    Long canvasId;

    @BeforeEach
    void setUp() {
        owner = user(jdbc);
        editor = user(jdbc);
        viewer = user(jdbc);
        stranger = user(jdbc);
        canvasId = canvasService.create(owner, new CanvasCreateRequest("예산 검토", "2026년 1분기", List.of())).id();
        canvasService.replaceShares(canvasId, owner, List.of(
                new ShareMember(editor, CanvasRole.EDIT), new ShareMember(viewer, CanvasRole.VIEW)));
    }

    @Test
    void 한_사람이_카드를_옮기는_동안_다른_사람이_카드_프롬프트를_고쳐도_둘_다_반영된다() {
        UUID card = addTable(owner);

        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card, "x", 300, "y", 120)));
        eventService.apply(canvasId, editor, batch(
                event("set_card_prompt", "cardId", card, "text", "감가상각비 추이 확인", "baseVersion", 0)));

        Map<String, Object> view = cardView(owner, card);
        assertThat(view.get("x")).isEqualTo(300.0);
        assertThat(view.get("y")).isEqualTo(120.0);
        assertThat(view.get("prompt")).isEqualTo("감가상각비 추이 확인");
        assertThat(view.get("promptVersion")).isEqualTo(1);
    }

    @Test
    void 같은_글을_오래된_기준_버전으로_고치면_409_K003과_현재_글을_돌려주고_묶음_전체를_반영하지_않는다() {
        UUID card = addTable(owner);
        eventService.apply(canvasId, owner, batch(
                event("set_card_prompt", "cardId", card, "text", "먼저 고친 글", "baseVersion", 0)));
        long seqBefore = lastSeq();

        CanvasEventsRequest late = batch(
                event("move_card", "cardId", card, "x", 999, "y", 999),
                event("set_card_prompt", "cardId", card, "text", "늦게 고친 글", "baseVersion", 0));

        assertThatThrownBy(() -> eventService.apply(canvasId, editor, late))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANVAS_TEXT_CONFLICT);
                    assertThat(e.getData()).isEqualTo(new TextConflict(card, "prompt", "먼저 고친 글", 1));
                });
        Map<String, Object> view = cardView(owner, card);
        assertThat(view.get("prompt")).isEqualTo("먼저 고친 글");
        assertThat(view.get("x")).isEqualTo(0.0);  // 같은 묶음의 이동도 반영되지 않음
        assertThat(lastSeq()).isEqualTo(seqBefore);
    }

    @Test
    void 프롬프트_카드_본문과_선_프롬프트도_버전으로_충돌을_막는다() {
        UUID prompt = UUID.randomUUID();
        UUID table = addTable(owner);
        UUID link = UUID.randomUUID();
        CanvasEventsResponse created = eventService.apply(canvasId, owner, batch(
                event("add_prompt", "cardId", prompt, "text", "초안 지시"),
                event("add_link", "linkId", link, "source", table, "target", prompt, "rel", "근거")));
        assertThat(created.textVersions().get(prompt.toString()).text()).isEqualTo(1);

        eventService.apply(canvasId, owner, batch(
                event("edit_prompt", "cardId", prompt, "text", "고친 지시", "baseVersion", 1),
                event("set_link_prompt", "linkId", link, "text", "이 근거로 설명", "baseVersion", 0)));

        assertThatThrownBy(() -> eventService.apply(canvasId, editor, batch(
                event("edit_prompt", "cardId", prompt, "text", "다른 지시", "baseVersion", 1))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_TEXT_CONFLICT);
        assertThatThrownBy(() -> eventService.apply(canvasId, editor, batch(
                event("set_link_prompt", "linkId", link, "text", "다른 설명", "baseVersion", 0))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_TEXT_CONFLICT);
    }

    @Test
    void 같은_requestId를_다시_보내면_저장하지_않고_처음_응답을_돌려준다() {
        UUID card = addTable(owner);
        CanvasEventsRequest request = batch(event("move_card", "cardId", card, "x", 10, "y", 10));

        CanvasEventsResponse first = eventService.apply(canvasId, owner, request);
        int eventsAfterFirst = events().size();
        CanvasEventsResponse retry = eventService.apply(canvasId, owner, request);

        assertThat(retry).isEqualTo(first);
        assertThat(events()).hasSize(eventsAfterFirst);
    }

    @Test
    void 보기_권한은_쓰기가_막히고_권한이_없으면_보기도_막힌다() {
        UUID card = addTable(owner);

        assertThatThrownBy(() -> eventService.apply(canvasId, viewer,
                batch(event("move_card", "cardId", card, "x", 1, "y", 1))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_EDIT_DENIED);
        assertThatThrownBy(() -> eventService.apply(canvasId, stranger,
                batch(event("move_card", "cardId", card, "x", 1, "y", 1))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_ACCESS_DENIED);
        assertThatThrownBy(() -> canvasService.open(canvasId, stranger))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_ACCESS_DENIED);
        assertThat(canvasService.open(canvasId, viewer).meta().myRole()).isEqualTo(CanvasRole.VIEW);
    }

    @Test
    void 한_묶음_안의_이동은_카드별_마지막_위치만_저장한다() {
        UUID card = addTable(owner);
        int before = events().size();

        eventService.apply(canvasId, owner, batch(
                event("move_card", "cardId", card, "x", 1, "y", 1),
                event("move_card", "cardId", card, "x", 2, "y", 2),
                event("move_card", "cardId", card, "x", 3, "y", 3)));

        assertThat(events()).hasSize(before + 1);
        assertThat(cardView(owner, card).get("x")).isEqualTo(3.0);
    }

    @Test
    void 카드를_지우면_닿는_연결과_비교_원본에서도_빠진다() {
        UUID a = addTable(owner);
        UUID b = addTable(owner);
        UUID compare = UUID.randomUUID();
        UUID link = UUID.randomUUID();
        eventService.apply(canvasId, owner, batch(
                event("create_compare", "cardId", compare, "sources", ids(a, b)),
                event("add_link", "linkId", link, "source", a, "target", b, "rel", "근거")));

        eventService.apply(canvasId, owner, batch(event("remove_card", "cardId", a)));

        CanvasDetailResponse opened = canvasService.open(canvasId, owner);
        assertThat(opened.links()).isEmpty();
        assertThat(cardView(owner, compare).get("sources")).isEqualTo(List.of(b.toString()));
        assertThat(opened.warnings()).isEmpty();
    }

    @Test
    void 다른_사람이_이미_지운_카드를_가리키는_수정은_무시하고_기록하지_않는다() {
        UUID card = addTable(owner);
        eventService.apply(canvasId, owner, batch(event("remove_card", "cardId", card)));
        int before = events().size();

        CanvasEventsResponse response = eventService.apply(canvasId, editor, batch(
                event("move_card", "cardId", card, "x", 5, "y", 5),
                event("edit_cell", "cardId", card, "r", 1, "c", 0, "value", 10)));

        assertThat(events()).hasSize(before);
        assertThat(response.lastSeq()).isEqualTo(lastSeq());
    }

    @Test
    void 이벤트_번호는_묶음이_여러_번_와도_1씩_이어진다() {
        UUID card = addTable(owner);
        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card, "x", 1, "y", 1)));
        eventService.apply(canvasId, editor, batch(event("edit_cell", "cardId", card, "r", 3, "c", 0, "value", 1.5)));

        List<Long> seqs = events().stream().map(CanvasEvent::getSeq).toList();
        for (int i = 0; i < seqs.size(); i++) {
            assertThat(seqs.get(i)).isEqualTo(i + 1L);
        }
        assertThat(lastSeq()).isEqualTo(seqs.size());
    }

    @Test
    void 두_사람이_동시에_보내도_이벤트_번호가_겹치거나_빠지지_않는다() throws Exception {
        UUID card = addTable(owner);
        long start = lastSeq();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Long who : List.of(owner, editor)) {
            tasks.add(() -> {
                for (int i = 0; i < 10; i++) {
                    eventService.apply(canvasId, who, batch(event("move_card", "cardId", card, "x", i, "y", i)));
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        List<Long> seqs = events().stream().map(CanvasEvent::getSeq).filter(s -> s > start).toList();
        assertThat(seqs).hasSize(20).doesNotHaveDuplicates();
        assertThat(seqs.get(seqs.size() - 1)).isEqualTo(start + 20);
    }

    @Test
    void 다시_열면_저장된_카드_연결_수정값이_그대로_복원된다() {
        UUID table = addTable(owner);
        UUID doc = UUID.randomUUID();
        eventService.apply(canvasId, owner, batch(
                event("add_doc", "cardId", doc, "docId", "news-12", "x", 40, "y", 50),
                event("edit_cell", "cardId", table, "r", 3, "c", 0, "value", 123.4),
                event("add_link", "linkId", UUID.randomUUID(), "source", table, "target", doc, "rel", "설명")));

        CanvasDetailResponse opened = canvasService.open(canvasId, editor);

        assertThat(opened.cards()).hasSize(2);
        assertThat(opened.links()).hasSize(1);
        assertThat(opened.lastSeq()).isEqualTo(lastSeq());
        assertThat(cardView(editor, table).get("edits")).isEqualTo(Map.of("3,0", 123.4));
        assertThat(cardView(editor, doc).get("docId")).isEqualTo("news-12");
    }

    @Test
    void 저장된_묶음은_커밋_후_전달용_알림을_발행한다() {
        UUID card = addTable(owner);
        published.clear();

        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card, "x", 7, "y", 7)));

        List<CanvasEventsCommitted> sent = published.stream(CanvasEventsCommitted.class).toList();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).firstSeq()).isEqualTo(sent.get(0).lastSeq()).isEqualTo(lastSeq());
        assertThat(sent.get(0).authorId()).isEqualTo(owner);
    }

    @Test
    void 모르는_이벤트나_빠진_필드는_400으로_거절한다() {
        assertThatThrownBy(() -> eventService.apply(canvasId, owner, batch(event("fly_card", "cardId", UUID.randomUUID()))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> eventService.apply(canvasId, owner, batch(event("add_card", "cardId", UUID.randomUUID()))))
                .hasMessageContaining("events[0].");
    }

    private UUID addTable(Long who) {
        UUID id = UUID.randomUUID();
        eventService.apply(canvasId, who, batch(event("add_card", "cardId", id,
                "tableId", "SKT_2026Q1_is", "rows", List.of(3, 5), "cols", List.of(0, 1))));
        return id;
    }

    private Map<String, Object> cardView(Long who, UUID cardId) {
        return canvasService.open(canvasId, who).cards().stream()
                .filter(c -> cardId.equals(c.get("id")))
                .findFirst().orElseThrow();
    }

    private List<CanvasEvent> events() {
        return eventRepository.findByCanvasIdOrderBySeqAsc(canvasId);
    }

    private long lastSeq() {
        return canvasService.open(canvasId, owner).lastSeq();
    }
}
