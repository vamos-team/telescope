package com.telescope.backend.canvas.controller;

import com.telescope.backend.canvas.dto.UserRef;
import com.telescope.backend.canvas.service.CanvasAccessService;
import com.telescope.backend.canvas.service.CanvasDestination;
import com.telescope.backend.canvas.service.CanvasPeople;
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
 *   <li>{@code SEND} 커서: 그 캔버스를 구독 중인 세션의 것만 전달한다. 구독 기록이 없거나 50ms보다 잦은 메시지는 조용히 버린다.</li>
 *   <li>{@code UNSUBSCRIBE}: 기록에서 빼고, 그 사람이 캔버스에서 완전히 나갔으면 나감을 알린다.</li>
 *   <li>알 수 없는 주소로의 구독·보내기는 막는다.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CanvasStompInterceptor implements ChannelInterceptor {

    private final CanvasAccessService access;
    private final CanvasRealtimeRegistry registry;
    private final CanvasPeople people;
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
            Long canvasId = topic.get().canvasId();
            requireRole(canvasId, userId);
            // 이름·부서는 구독할 때 한 번만 읽는다. 커서·접속자 알림은 이 값을 쓴다(#149)
            UserRef person = people.ref(userId);
            boolean joined = registry.add(new CanvasRealtimeRegistry.Subscription(accessor.getSessionId(),
                    accessor.getSubscriptionId(), canvasId, person, topic.get().channel()));
            // 첫 확인과 기록 사이에 공유가 풀렸을 수 있다(회수 처리는 아직 기록이 없어 지울 것이 없었음).
            // 기록한 뒤 다시 확인한다: 권한이 있으면 이후의 회수 처리가 이 기록을 지우고, 없으면 여기서 지운다.
            if (access.roleOf(canvasId, userId).isEmpty()) {
                registry.remove(accessor.getSessionId(), accessor.getSubscriptionId());
                throw new MessageDeliveryException("K001 이 캔버스를 볼 권한이 없습니다.");
            }
            if (joined) {
                notifier.getObject().presence(canvasId, "join", person);
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
                .forEach(s -> notifier.getObject().presence(s.canvasId(), "leave", s.user()));
        return message;
    }

    private Message<?> send(StompHeaderAccessor accessor, Message<?> message) {
        Optional<CanvasDestination> app = CanvasDestination.app(accessor.getDestination());
        if (app.isEmpty() || !CanvasDestination.CURSOR_SEND.equals(app.get().channel())) {
            throw new MessageDeliveryException("C001 보낼 수 없는 주소입니다: " + accessor.getDestination());
        }
        Long canvasId = app.get().canvasId();
        // 구독 기록이 없으면 조용히 버린다. 같은 세션의 프레임이 병렬로 처리돼 구독 직후의 커서가 구독보다 먼저 올 수 있는데,
        // 이때 연결을 닫으면 권한 있는 사람도 다시 연결해야 한다. 커서는 50ms 뒤 다음 위치가 오므로 한 번 버려도 된다.
        // 권한이 없는 사람은 애초에 구독 기록이 없으므로 커서를 보낼 수 없다.
        if (!registry.isSubscribed(accessor.getSessionId(), canvasId)) {
            return null;
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
