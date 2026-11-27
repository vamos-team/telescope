package com.telescope.canvas.global.config;

import com.telescope.canvas.canvas.controller.CanvasOutboundInterceptor;
import com.telescope.canvas.canvas.controller.CanvasStompInterceptor;
import com.telescope.common.auth.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
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

    /** 하트비트용 스케줄러. 프레임워크가 관리하는 빈을 받아 써서 컨텍스트가 닫힐 때 함께 정리되게 한다. */
    private TaskScheduler heartbeatScheduler;

    /**
     * 프레임워크의 {@code messageBrokerTaskScheduler} 빈을 받는다. 브로커 설정보다 늦게 만들어지므로 지연 주입한다.
     * 직접 만든 스케줄러는 빈이 아니라 종료 때 스레드가 남는다.
     */
    @Autowired
    public void setHeartbeatScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler heartbeatScheduler) {
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /**
     * STOMP 연결 주소를 등록한다.
     *
     * <p>수신 순서 보존({@code setPreserveReceiveOrder})은 켜지 않는다. 켜면 프레임을 별도 실행기에서 처리해
     * 인증·권한 거절이 화면에 {@code ERROR} 프레임으로 전달되지 않고 연결이 멈춘다(테스트로 확인).
     * 구독 직후의 커서가 구독 기록보다 먼저 처리되는 경우는 커서를 조용히 버리는 것으로 다룬다(docs/backend/canvas.md D20).
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/api/v1/ws").setAllowedOriginPatterns(allowedOriginPatterns);
    }

    /**
     * 메모리 브로커와 주소 접두어(앱 {@code /app}, 개인 {@code /user}), 하트비트를 설정한다.
     * 같은 세션으로 나가는 메시지는 발행한 순서대로 보낸다(접속자 들어옴·나감 순서가 뒤바뀌지 않도록).
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MILLIS, HEARTBEAT_MILLIS})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.setPreservePublishOrder(true);
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
