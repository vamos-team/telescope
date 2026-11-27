package com.telescope.backend.canvas.controller;

import com.telescope.backend.canvas.service.CanvasDestination;
import com.telescope.backend.canvas.service.CanvasRealtimeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 화면으로 나가는 캔버스 메시지를 거른다. 권한 확인을 통과해 {@link CanvasRealtimeRegistry}에 기록된 세션에만 보낸다.
 *
 * <p>메시지 브로커는 구독을 서버가 하나씩 끊는 방법을 주지 않는다. 그래서 공유가 풀리면 기록에서 빼고,
 * 여기서 그 세션으로 가는 캔버스 메시지를 버린다. 결과적으로 공유가 풀린 순간부터 더 받지 못한다.
 */
@Component
@RequiredArgsConstructor
public class CanvasOutboundInterceptor implements ChannelInterceptor {

    private final CanvasRealtimeRegistry registry;

    /** 캔버스 구독 주소로 가는 메시지인데 그 세션의 기록이 없으면 버린다(null). 그 밖의 메시지는 그대로 보낸다. */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        if (accessor.getMessageType() != SimpMessageType.MESSAGE) {
            return message;
        }
        Optional<CanvasDestination> topic = CanvasDestination.topic(accessor.getDestination());
        if (topic.isPresent() && !registry.isSubscribed(accessor.getSessionId(), topic.get().canvasId())) {
            return null;
        }
        return message;
    }
}
