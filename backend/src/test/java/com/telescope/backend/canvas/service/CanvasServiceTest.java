package com.telescope.backend.canvas.service;

import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.canvas.dto.CanvasActivityResponse;
import com.telescope.backend.canvas.dto.CanvasCreateRequest;
import com.telescope.backend.canvas.dto.CanvasSharesResponse;
import com.telescope.backend.canvas.dto.CanvasSummaryResponse;
import com.telescope.backend.canvas.dto.ShareMember;
import com.telescope.backend.canvas.dto.ShareMemberResponse;
import com.telescope.backend.canvas.entity.CanvasRole;
import com.telescope.backend.global.config.JpaAuditingConfig;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.user.service.UserService;
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
import java.util.Set;

import static com.telescope.backend.canvas.service.CanvasTestSupport.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 캔버스 목록·생성·이름 변경·삭제·공유 규칙을 실제로 커밋되는 상태에서 확인한다. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, JpaAuditingConfig.class, CanvasService.class, CanvasEventService.class,
        CanvasAccessService.class, CanvasPeople.class, UserService.class, CanvasServiceTest.TestBeans.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RecordApplicationEvents
class CanvasServiceTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }

    @Autowired CanvasService canvasService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents published;

    Long owner;
    Long editor;
    Long viewer;
    Long stranger;

    @BeforeEach
    void setUp() {
        owner = user(jdbc);
        editor = user(jdbc);
        viewer = user(jdbc);
        stranger = user(jdbc);
    }

    @Test
    void 만들_때_편집할_사람을_바로_초대하고_없는_사용자면_404() {
        CanvasSummaryResponse created = canvasService.create(owner,
                new CanvasCreateRequest(" ", "2026년 1분기", List.of(editor, owner)));

        assertThat(created.name()).isEqualTo("제목 없는 캔버스");
        assertThat(created.myRole()).isEqualTo(CanvasRole.OWNER);
        assertThat(canvasService.shares(created.id(), editor).members())
                .extracting(ShareMemberResponse::userId, ShareMemberResponse::role)
                .containsExactly(tuple(editor, CanvasRole.EDIT));
        assertThatThrownBy(() -> canvasService.create(owner, new CanvasCreateRequest("x", "2026년 1분기", List.of(-1L))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    void 목록은_내가_소유하거나_초대받은_캔버스와_내_권한을_보여준다() {
        Long mine = canvas(owner);
        Long theirs = canvas(stranger);
        canvasService.replaceShares(theirs, stranger, List.of(new ShareMember(owner, CanvasRole.VIEW)));

        assertThat(canvasService.list(owner, "all")).extracting(CanvasSummaryResponse::id, CanvasSummaryResponse::myRole)
                .contains(org.assertj.core.groups.Tuple.tuple(mine, CanvasRole.OWNER),
                        org.assertj.core.groups.Tuple.tuple(theirs, CanvasRole.VIEW));
        assertThat(canvasService.list(owner, "mine")).extracting(CanvasSummaryResponse::id).containsExactly(mine);
        assertThat(canvasService.list(owner, "shared")).extracting(CanvasSummaryResponse::id).containsExactly(theirs);
        assertThat(canvasService.list(editor, "all")).isEmpty();
    }

    @Test
    void 공유_설정은_소유자만_바꾼다() {
        Long id = canvas(owner);
        canvasService.replaceShares(id, owner, List.of(new ShareMember(editor, CanvasRole.EDIT)));

        assertThatThrownBy(() -> canvasService.replaceShares(id, editor, List.of()))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_EDIT_DENIED);
        assertThatThrownBy(() -> canvasService.replaceShares(id, owner, List.of(new ShareMember(owner, CanvasRole.EDIT))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> canvasService.replaceShares(id, owner, List.of(
                new ShareMember(viewer, CanvasRole.VIEW), new ShareMember(viewer, CanvasRole.EDIT))))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    void 공유에서_빼면_바로_볼_수_없고_변경은_활동_로그와_알림으로_남는다() {
        Long id = canvas(owner);
        canvasService.replaceShares(id, owner, List.of(
                new ShareMember(editor, CanvasRole.EDIT), new ShareMember(viewer, CanvasRole.VIEW)));
        published.clear();

        CanvasSharesResponse after = canvasService.replaceShares(id, owner, List.of(new ShareMember(viewer, CanvasRole.EDIT)));

        assertThat(after.members()).extracting(ShareMemberResponse::userId, ShareMemberResponse::role)
                .containsExactly(tuple(viewer, CanvasRole.EDIT));
        assertThatThrownBy(() -> canvasService.open(id, editor))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_ACCESS_DENIED);
        assertThat(canvasService.activities(id, viewer)).extracting(CanvasActivityResponse::text)
                .first().isEqualTo("공유 권한을 바꿈");
        CanvasAccessChanged changed = published.stream(CanvasAccessChanged.class).findFirst().orElseThrow();
        assertThat(changed.revokedUserIds()).containsExactly(editor);
        assertThat(changed.changedRoles()).isEqualTo(Map.of(viewer, CanvasRole.EDIT));
        assertThat(published.stream(CanvasEventsCommitted.class)).hasSize(1);
    }

    @Test
    void 이름_변경은_소유자와_편집만_삭제는_소유자만_하고_삭제된_캔버스는_404() {
        Long id = canvas(owner);
        canvasService.replaceShares(id, owner, List.of(
                new ShareMember(editor, CanvasRole.EDIT), new ShareMember(viewer, CanvasRole.VIEW)));

        assertThat(canvasService.rename(id, editor, "새 이름").name()).isEqualTo("새 이름");
        assertThatThrownBy(() -> canvasService.rename(id, viewer, "x"))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_EDIT_DENIED);
        assertThatThrownBy(() -> canvasService.delete(id, editor))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.CANVAS_EDIT_DENIED);

        published.clear();
        canvasService.delete(id, owner);

        assertThatThrownBy(() -> canvasService.open(id, owner))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(canvasService.list(editor, "all")).extracting(CanvasSummaryResponse::id).doesNotContain(id);
        CanvasAccessChanged deleted = published.stream(CanvasAccessChanged.class).findFirst().orElseThrow();
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.revokedUserIds()).isEqualTo(Set.of(editor, viewer));
    }

    @Test
    void 목록_filter가_잘못되면_400() {
        assertThatThrownBy(() -> canvasService.list(owner, "everything"))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT);
    }

    private Long canvas(Long who) {
        return canvasService.create(who, new CanvasCreateRequest("캔버스", "2026년 1분기", List.of())).id();
    }
}
