package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.CanvasEventsMessage;
import com.telescope.backend.canvas.dto.CanvasUserNotice;
import com.telescope.backend.canvas.dto.CursorMessage;
import com.telescope.backend.canvas.dto.PresenceMessage;
import com.telescope.backend.canvas.dto.UserRef;
import com.telescope.backend.canvas.entity.CanvasRole;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;

/**
 * 캔버스 실시간 메시지를 보낸다. 규약은 {@code docs/backend/api-spec.md} 5.5·5.6.
 *
 * <p>이벤트·권한 변경은 트랜잭션이 커밋된 뒤에만 보낸다({@code AFTER_COMMIT}). 롤백된 묶음(글 충돌 등)은 보내지 않는다.
 * 보내는 쪽 필터({@link CanvasRealtimeRegistry})가 권한 확인을 통과한 구독에만 캔버스 메시지를 전달한다.
 */
@Service
@RequiredArgsConstructor
public class CanvasRealtimeNotifier {

    private final SimpMessagingTemplate messaging;
    private final CanvasRealtimeRegistry registry;

    /** 커밋된 이벤트 묶음을 그 캔버스 구독자에게 보낸다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventsCommitted(CanvasEventsCommitted committed) {
        messaging.convertAndSend(CanvasDestination.topicOf(committed.canvasId(), CanvasDestination.EVENTS),
                new CanvasEventsMessage(committed.firstSeq(), committed.lastSeq(), committed.requestId(),
                        new UserRef(committed.authorId()), committed.events(), committed.textVersions()));
    }

    /**
     * 공유 변경·캔버스 삭제가 커밋되면, 권한이 사라진 사람의 구독을 기록에서 빼서 더 받지 못하게 하고
     * 개인 알림을 보낸다. 권한이 바뀐 사람에게도 알려 화면이 편집 도구를 맞추게 한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccessChanged(CanvasAccessChanged changed) {
        List<CanvasRealtimeRegistry.Subscription> left = registry.revoke(changed.canvasId(), changed.revokedUserIds());
        String type = changed.deleted() ? "deleted" : "revoked";
        for (Long userId : changed.revokedUserIds()) {
            notifyUser(userId, new CanvasUserNotice(type, changed.canvasId(), null));
        }
        for (Map.Entry<Long, CanvasRole> e : changed.changedRoles().entrySet()) {
            notifyUser(e.getKey(), new CanvasUserNotice("role_changed", changed.canvasId(), e.getValue()));
        }
        left.forEach(s -> presence(s.canvasId(), "leave", s.userId()));
    }

    /** 다른 사람에게 커서 위치를 보낸다(저장하지 않음). */
    public void cursor(Long canvasId, Long userId, double x, double y) {
        messaging.convertAndSend(CanvasDestination.topicOf(canvasId, CanvasDestination.CURSORS),
                new CursorMessage(new UserRef(userId), x, y));
    }

    /** 접속자 들어옴({@code join})·나감({@code leave})을 그 캔버스 구독자에게 알린다. */
    public void presence(Long canvasId, String type, Long userId) {
        messaging.convertAndSend(CanvasDestination.topicOf(canvasId, CanvasDestination.PRESENCE),
                new PresenceMessage(type, new UserRef(userId)));
    }

    private void notifyUser(Long userId, CanvasUserNotice notice) {
        messaging.convertAndSendToUser(String.valueOf(userId), "/queue/canvases", notice);
    }
}
