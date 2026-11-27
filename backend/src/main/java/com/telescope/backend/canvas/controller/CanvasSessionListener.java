package com.telescope.backend.canvas.controller;

import com.telescope.backend.canvas.service.CanvasRealtimeNotifier;
import com.telescope.backend.canvas.service.CanvasRealtimeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** WebSocket 연결이 끊기면(창 닫기, 네트워크, 서버 배포) 그 세션의 구독을 지우고 나감을 알린다. */
@Component
@RequiredArgsConstructor
public class CanvasSessionListener {

    private final CanvasRealtimeRegistry registry;
    private final CanvasRealtimeNotifier notifier;

    /** 세션의 구독을 모두 지우고, 이 때문에 캔버스에서 완전히 나간 사람의 나감을 알린다. */
    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        registry.removeSession(event.getSessionId())
                .forEach(s -> notifier.presence(s.canvasId(), "leave", s.user()));
    }
}
