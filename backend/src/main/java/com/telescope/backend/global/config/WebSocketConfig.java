package com.telescope.backend.global.config;

import com.telescope.backend.canvas.controller.CanvasOutboundInterceptor;
import com.telescope.backend.canvas.controller.CanvasStompInterceptor;
import com.telescope.backend.global.auth.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * 실시간 전달(WebSocket + STOMP) 설정. 규약은 {@code docs/backend/api-spec.md} 5.5, 선정 이유는 11장.
 *
 * <ul>
 *   <li>주소 {@code /api/v1/ws}: Ingress의 {@code /api} 경로로 backend에 온다. SockJS 대체 경로는 쓰지 않는다.</li>
 *   <li>서버 안 메모리 브로커({@code /topic}, {@code /queue}). backend 파드가 1개라 충분하다.</li>
 *   <li>하트비트 10초: Ingress(nginx)의 기본 60초 무응답 끊김보다 짧게 둔다.</li>
 *   <li>들어오는 프레임은 인증 → 캔버스 권한 순서로 확인하고, 나가는 캔버스 메시지는 권한이 남아 있는 세션에만 보낸다.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MILLIS = 10_000;

    private final StompAuthChannelInterceptor authInterceptor;
    private final CanvasStompInterceptor canvasInterceptor;
    private final CanvasOutboundInterceptor outboundInterceptor;

    /** 허용할 화면 출처. 인증은 토큰으로 하므로 기본은 모두 허용하고, 필요하면 환경변수로 좁힌다. */
    @Value("${app.websocket.allowed-origin-patterns:*}")
    private String[] allowedOriginPatterns;

    /** STOMP 연결 주소를 등록한다. */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/v1/ws").setAllowedOriginPatterns(allowedOriginPatterns);
    }

    /** 메모리 브로커와 주소 접두어(앱 {@code /app}, 개인 {@code /user}), 하트비트를 설정한다. */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        ThreadPoolTaskScheduler heartbeat = new ThreadPoolTaskScheduler();
        heartbeat.setPoolSize(1);
        heartbeat.setThreadNamePrefix("stomp-heartbeat-");
        heartbeat.initialize();
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(heartbeat);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    /** 들어오는 프레임: 인증을 먼저 하고 캔버스 권한을 확인한다. */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor, canvasInterceptor);
    }

    /** 나가는 메시지: 권한이 남아 있는 세션에만 캔버스 메시지를 보낸다. */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.interceptors(outboundInterceptor);
    }
}
