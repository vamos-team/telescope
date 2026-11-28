package com.telescope.canvas.canvas.controller;

import com.telescope.canvas.canvas.dto.CursorPosition;
import com.telescope.canvas.canvas.dto.PresenceSnapshot;
import com.telescope.canvas.canvas.dto.UserRef;
import com.telescope.canvas.canvas.service.CanvasRealtimeNotifier;
import com.telescope.canvas.canvas.service.CanvasRealtimeRegistry;
import com.telescope.common.auth.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SubscribeMapping;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * 캔버스 STOMP 메시지 처리. 권한 확인은 {@link CanvasStompInterceptor}가 먼저 끝낸다.
 * 경로 앞의 {@code /app}은 설정의 앱 주소 접두어다.
 */
@Controller
@RequiredArgsConstructor
public class CanvasRealtimeController {

    private final CanvasRealtimeNotifier notifier;
    private final CanvasRealtimeRegistry registry;

    /** 내 커서 위치를 같은 캔버스 사람들에게 보낸다({@code /app/canvases/{id}/cursor}). 형식이 틀리면 버린다. */
    @MessageMapping("/canvases/{canvasId}/cursor")
    public void cursor(@DestinationVariable Long canvasId, @Payload CursorPosition position, Principal principal,
                       @Header(SimpMessageHeaderAccessor.SESSION_ID_HEADER) String sessionId) {
        if (position == null || !position.isValid() || !(principal instanceof UserPrincipal user)) {
            return;
        }
        // 구독할 때 읽어 둔 이름을 쓴다(커서마다 DB를 보지 않게, #149). 구독 기록이 없으면 인터셉터가 이미 버렸다
        UserRef person = registry.personOf(sessionId, canvasId).orElseGet(() -> UserRef.unknown(user.id()));
        notifier.cursor(canvasId, person, position.x(), position.y());
    }

    /** 처음 구독할 때 지금 접속자 목록을 한 번 돌려준다({@code /app/canvases/{id}/presence}). */
    @SubscribeMapping("/canvases/{canvasId}/presence")
    public PresenceSnapshot presence(@DestinationVariable Long canvasId) {
        return new PresenceSnapshot(registry.presentUsers(canvasId));
    }
}
