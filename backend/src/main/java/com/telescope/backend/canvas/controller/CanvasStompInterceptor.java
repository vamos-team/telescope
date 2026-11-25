package com.telescope.backend.canvas.controller;

import com.telescope.backend.canvas.service.CanvasAccessService;
import com.telescope.backend.canvas.service.CanvasDestination;
import com.telescope.backend.canvas.service.CanvasRealtimeNotifier;
import com.telescope.backend.canvas.service.CanvasRealtimeRegistry;
import com.telescope.backend.global.auth.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 화면에서 들어오는 캔버스 STOMP 프레임의 권한을 확인한다(인증은 {@code StompAuthChannelInterceptor}가 먼저 한다).
 *
 * <ul>
 *   <li>{@code SUBSCRIBE}: 캔버스 주소면 그 캔버스 권한(소유자·편집·보기)이 있어야 한다. 없으면 {@code ERROR}(K001).
 *       통과한 구독은 {@link CanvasRealtimeRegistry}에 기록하고, 접속자 구독이면 들어옴을 알린다.</li>
 *   <li>{@code SEND} 커서: 그 캔버스를 구독 중인 세션만 보낼 수 있고, 50ms보다 잦은 메시지는 버린다.</li>
 *   <li>{@code UNSUBSCRIBE}: 기록에서 빼고, 그 사람이 캔버스에서 완전히 나갔으면 나감을 알린다.</li>
 *   <li>알 수 없는 주소로의 구독·보내기는 막는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CanvasStompInterceptor implements ChannelInterceptor {

    private final CanvasAccessService access;
    private final CanvasRealtimeRegistry registry;
    /** 메시지 전송 빈은 WebSocket 설정이 끝난 뒤 만들어지므로 늦게 가져온다(순환 참조 방지). */
    private final ObjectProvider<CanvasRealtimeNotifier> notifier;

    /**
     * 들어오는 프레임을 확인한다.
     *
     * @return 그대로 통과시킬 메시지. 커서 빈도 제한에 걸리면 null(조용히 버림)
     * @throws MessageDeliveryException 권한이 없거나 알 수 없는 주소. 화면은 STOMP {@code ERROR}를 받는다
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        return switch (accessor.getCommand()) {
            case SUBSCRIBE -> subscribe(accessor, message);
            case UNSUBSCRIBE -> unsubscribe(accessor, message);
            case SEND -> send(accessor, message);
            default -> message;
        };
    }

    private Message<?> subscribe(StompHeaderAccessor accessor, Message<?> message) {
        String destination = accessor.getDestination();
        if (CanvasDestination.USER_QUEUE.equals(destination)) {
            return message;
        }
        Long userId = userId(accessor);
        Optional<CanvasDestination> topic = CanvasDestination.topic(destination);
        if (topic.isPresent()) {
            requireRole(topic.get().canvasId(), userId);
            boolean joined = registry.add(new CanvasRealtimeRegistry.Subscription(accessor.getSessionId(),
                    accessor.getSubscriptionId(), topic.get().canvasId(), userId, topic.get().channel()));
            if (joined) {
                notifier.getObject().presence(topic.get().canvasId(), "join", userId);
            }
            return message;
        }
        Optional<CanvasDestination> app = CanvasDestination.app(destination);
        if (app.isPresent() && CanvasDestination.PRESENCE_SNAPSHOT.equals(app.get().channel())) {
            requireRole(app.get().canvasId(), userId);
            return message;
        }
        throw new MessageDeliveryException("C001 구독할 수 없는 주소입니다: " + destination);
    }

    private Message<?> unsubscribe(StompHeaderAccessor accessor, Message<?> message) {
        registry.remove(accessor.getSessionId(), accessor.getSubscriptionId())
                .forEach(s -> notifier.getObject().presence(s.canvasId(), "leave", s.userId()));
        return message;
    }

    private Message<?> send(StompHeaderAccessor accessor, Message<?> message) {
        Optional<CanvasDestination> app = CanvasDestination.app(accessor.getDestination());
        if (app.isEmpty() || !CanvasDestination.CURSOR_SEND.equals(app.get().channel())) {
            throw new MessageDeliveryException("C001 보낼 수 없는 주소입니다: " + accessor.getDestination());
        }
        Long canvasId = app.get().canvasId();
        if (!registry.isSubscribed(accessor.getSessionId(), canvasId)) {
            throw new MessageDeliveryException("K001 이 캔버스를 볼 권한이 없습니다.");
        }
        return registry.allowCursor(accessor.getSessionId(), canvasId, System.nanoTime()) ? message : null;
    }

    private void requireRole(Long canvasId, Long userId) {
        if (access.roleOf(canvasId, userId).isEmpty()) {
            throw new MessageDeliveryException("K001 이 캔버스를 볼 권한이 없습니다.");
        }
    }

    private static Long userId(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof UserPrincipal principal) {
            return principal.id();
        }
        throw new MessageDeliveryException("A002 인증이 필요합니다.");
    }
}
