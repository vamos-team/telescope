package com.telescope.canvas.canvas.service;

import com.telescope.canvas.CanvasApplication;
import org.springframework.test.context.ContextConfiguration;
import com.telescope.common.test.TestcontainersConfig;
import com.telescope.canvas.canvas.dto.CanvasActivityResponse;
import com.telescope.canvas.canvas.dto.CanvasCreateRequest;
import com.telescope.canvas.canvas.dto.CanvasDetailResponse;
import com.telescope.canvas.canvas.dto.CanvasSharesResponse;
import com.telescope.canvas.canvas.dto.CanvasSummaryResponse;
import com.telescope.canvas.canvas.dto.ShareMember;
import com.telescope.canvas.canvas.dto.ShareMemberResponse;
import com.telescope.canvas.canvas.dto.UserRef;
import com.telescope.canvas.canvas.entity.CanvasRole;
import com.telescope.common.config.JpaAuditingConfig;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.telescope.canvas.canvas.service.CanvasTestSupport.batch;
import static com.telescope.canvas.canvas.service.CanvasTestSupport.event;
import static com.telescope.canvas.canvas.service.CanvasTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 캔버스 응답의 사람 정보에 이름·부서가 채워지는지(#149). 응답 하나에 사람 조회는 한 번이어야 한다(N+1 없음).
 * user 서비스는 실제 구현을 감싸 호출 횟수만 센다.
 */
@DataJpaTest
@ContextConfiguration(classes = CanvasApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class, CanvasService.class, CanvasEventService.class,
        CanvasAccessService.class, CanvasPeople.class, StubUserDirectory.class, CanvasPeopleNamesTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
class CanvasPeopleNamesTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }

    @Autowired CanvasService canvasService;
    @Autowired CanvasEventService eventService;
    @Autowired JdbcTemplate jdbc;
    @Autowired StubUserDirectory directory;
    @Autowired ApplicationEvents published;

    Long owner;
    Long editor;
    Long viewer;
    Long canvasId;

    @BeforeEach
    void setUp() {
        owner = user(jdbc, "이재무", "네트워크센터");
        editor = user(jdbc, "최예산", "AIDC사업본부");
        viewer = user(jdbc, "오마케", null);
        directory.register(owner, "이재무", "네트워크센터");
        directory.register(editor, "최예산", "AIDC사업본부");
        directory.register(viewer, "오마케", null);
        canvasId = canvasService.create(owner, new CanvasCreateRequest("예산 검토", "2026년 1분기", List.of(editor))).id();
        canvasService.replaceShares(canvasId, owner,
                List.of(new ShareMember(editor, CanvasRole.EDIT), new ShareMember(viewer, CanvasRole.VIEW)));
    }

    @Test
    void 목록의_소유자와_마지막_수정자에_이름을_붙이고_사람_조회는_한_번이다() {
        eventService.apply(canvasId, editor, batch(event("add_memo", "cardId", UUID.randomUUID(), "x", 0, "y", 0, "text", "메모")));
        canvasService.create(editor, new CanvasCreateRequest("두 번째", "2026년 1분기", List.of(owner)));
        directory.resetCalls();

        List<CanvasSummaryResponse> list = canvasService.list(owner, "all");

        assertThat(list).hasSize(2);
        CanvasSummaryResponse mine = list.stream().filter(c -> c.id().equals(canvasId)).findFirst().orElseThrow();
        assertThat(mine.owner()).isEqualTo(new UserRef(owner, "이재무", "네트워크센터"));
        assertThat(mine.updatedBy()).isEqualTo(new UserRef(editor, "최예산", "AIDC사업본부"));
        assertThat(directory.calls()).isEqualTo(1);
    }

    @Test
    void 열기의_카드_작성자와_선_메모_작성자에_이름을_붙이고_사람_조회는_한_번이다() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID link = UUID.randomUUID();
        eventService.apply(canvasId, owner, batch(event("add_memo", "cardId", a, "x", 0, "y", 0, "text", "가")));
        eventService.apply(canvasId, editor, batch(event("add_memo", "cardId", b, "x", 1, "y", 1, "text", "나")));
        eventService.apply(canvasId, owner, batch(event("add_link", "linkId", link, "source", a, "target", b)));
        eventService.apply(canvasId, editor, batch(event("set_link_memo", "linkId", link, "text", "근거", "baseVersion", 0)));
        directory.resetCalls();

        CanvasDetailResponse opened = canvasService.open(canvasId, viewer);

        Map<String, Object> cardB = opened.cards().stream().filter(c -> c.get("id").equals(b)).findFirst().orElseThrow();
        assertThat(cardB.get("createdBy")).isEqualTo(new UserRef(editor, "최예산", "AIDC사업본부"));
        assertThat(opened.links().getFirst().memoBy()).isEqualTo(new UserRef(editor, "최예산", "AIDC사업본부"));
        assertThat(opened.meta().owner().name()).isEqualTo("이재무");
        assertThat(directory.calls()).isEqualTo(1);
    }

    @Test
    void 활동_로그의_작성자에_이름을_붙인다() {
        eventService.apply(canvasId, editor, batch(event("add_memo", "cardId", UUID.randomUUID(), "x", 0, "y", 0, "text", "메모")));
        directory.resetCalls();

        List<CanvasActivityResponse> activities = canvasService.activities(canvasId, viewer);

        assertThat(activities.getFirst().author()).isEqualTo(new UserRef(editor, "최예산", "AIDC사업본부"));
        assertThat(directory.calls()).isEqualTo(1);
    }

    @Test
    void 공유_명단에_소유자와_명단의_이름_부서를_붙이고_부서가_없으면_null이다() {
        CanvasSharesResponse shares = canvasService.shares(canvasId, editor);

        assertThat(shares.ownerId()).isEqualTo(owner);
        assertThat(shares.owner()).isEqualTo(new UserRef(owner, "이재무", "네트워크센터"));
        assertThat(shares.members())
                .extracting(ShareMemberResponse::userId, ShareMemberResponse::role, ShareMemberResponse::name, ShareMemberResponse::dept)
                .containsExactly(tuple(editor, CanvasRole.EDIT, "최예산", "AIDC사업본부"), tuple(viewer, CanvasRole.VIEW, "오마케", null));
    }

    @Test
    void 사용자를_찾지_못하면_ID만_두고_이름은_비운다() {
        CanvasPeople people = new CanvasPeople(directory);

        Map<Long, UserRef> refs = people.refs(List.of(owner, -1L));

        assertThat(refs.get(owner).name()).isEqualTo("이재무");
        assertThat(refs.get(-1L)).isEqualTo(UserRef.unknown(-1L));
    }

    @Test
    void 변경_알림의_작성자는_저장할_때_이름까지_담는다() {
        published.clear();

        eventService.apply(canvasId, editor, batch(event("add_memo", "cardId", UUID.randomUUID(), "x", 0, "y", 0, "text", "메모")));

        // 실시간 전달은 커밋 뒤 이 값을 그대로 보낸다(리스너가 DB를 다시 보지 않음)
        assertThat(published.stream(CanvasEventsCommitted.class).toList())
                .extracting(CanvasEventsCommitted::author)
                .containsExactly(new UserRef(editor, "최예산", "AIDC사업본부"));
    }
}
