package com.telescope.backend.canvas.controller;

import com.telescope.backend.TestcontainersConfig;
import com.telescope.backend.canvas.dto.CanvasCreateRequest;
import com.telescope.backend.canvas.dto.CanvasEventsRequest;
import com.telescope.backend.canvas.dto.ShareMember;
import com.telescope.backend.canvas.entity.CanvasRole;
import com.telescope.backend.canvas.service.CanvasEventService;
import com.telescope.backend.canvas.service.CanvasService;
import com.telescope.backend.global.auth.AccessTokenVerifier;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 WebSocket(STOMP) 클라이언트로 붙어 실시간 전달 규칙을 확인한다. 규약: {@code docs/backend/api-spec.md} 5.5·5.6.
 *
 * <p>인증(#34) 전이므로 테스트용 토큰 검증기("user-{id}" → id)를 쓴다. 구독이 브로커에 실제로 등록됐는지는
 * 시간이 아니라 브로커의 구독 목록을 조회해 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfig.class, CanvasRealtimeTest.TestAuth.class})
class CanvasRealtimeTest {

    private static final long WAIT_SECONDS = 5;

    @TestConfiguration
    static class TestAuth {
        @Bean
        AccessTokenVerifier testTokenVerifier() {
            return token -> {
                if (token.startsWith("user-")) {
                    return Long.valueOf(token.substring("user-".length()));
                }
                throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            };
        }
    }

    @LocalServerPort int port;
    @Autowired CanvasService canvasService;
    @Autowired CanvasEventService eventService;
    @Autowired SimpleBrokerMessageHandler broker;
    @Autowired JdbcTemplate jdbc;

    WebSocketStompClient client;
    final List<StompSession> sessions = new ArrayList<>();
    Long owner;
    Long editor;
    Long viewer;
    Long stranger;
    Long canvasId;
    UUID card;

    @BeforeEach
    void setUp() {
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter());
        owner = user();
        editor = user();
        viewer = user();
        stranger = user();
        canvasId = canvasService.create(owner, new CanvasCreateRequest("실시간", "2026년 1분기", List.of())).id();
        canvasService.replaceShares(canvasId, owner, List.of(
                new ShareMember(editor, CanvasRole.EDIT), new ShareMember(viewer, CanvasRole.VIEW)));
        card = UUID.randomUUID();
        eventService.apply(canvasId, owner, batch(event("add_card", "cardId", card.toString(),
                "tableId", "SKT_2026Q1_is", "rows", List.of(1), "cols", List.of(0))));
    }

    @AfterEach
    void tearDown() {
        sessions.forEach(s -> {
            if (s.isConnected()) {
                s.disconnect();
            }
        });
    }

    @Test
    void 커밋된_이벤트_묶음은_권한자_모두에게_간다() throws Exception {
        BlockingQueue<Map<String, Object>> toViewer = subscribe(connect(viewer), events());
        BlockingQueue<Map<String, Object>> toEditor = subscribe(connect(editor), events());
        awaitSubscribers(events(), 2);

        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card.toString(), "x", 10, "y", 20)));

        Map<String, Object> received = toViewer.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(received).isNotNull();
        assertThat(received.get("firstSeq")).isEqualTo(received.get("lastSeq"));
        assertThat(received.get("author")).isEqualTo(Map.of("id", owner.intValue()));
        assertThat((List<?>) received.get("events")).hasSize(1);
        assertThat(toEditor.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void 롤백된_묶음은_보내지_않는다() throws Exception {
        BlockingQueue<Map<String, Object>> toViewer = subscribe(connect(viewer), events());
        awaitSubscribers(events(), 1);
        eventService.apply(canvasId, owner, batch(
                event("set_card_memo", "cardId", card.toString(), "text", "먼저", "baseVersion", 0)));
        assertThat(toViewer.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();

        assertThatThrownBy(() -> eventService.apply(canvasId, editor, batch(
                event("set_card_memo", "cardId", card.toString(), "text", "늦게", "baseVersion", 0))))
                .isInstanceOf(BusinessException.class);
        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card.toString(), "x", 1, "y", 1)));

        // 롤백된 묶음이 갔다면 이동보다 먼저 도착했어야 한다. 다음 메시지는 이동이어야 한다
        Map<String, Object> next = toViewer.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(next).isNotNull();
        assertThat(((Map<?, ?>) ((List<?>) next.get("events")).get(0)).get("type")).isEqualTo("move_card");
    }

    @Test
    void 토큰이_없거나_틀리면_연결이_거절된다() {
        assertThatThrownBy(() -> connectWith(null)).isInstanceOf(ExecutionException.class);
        assertThatThrownBy(() -> connectWith("Bearer wrong")).isInstanceOf(ExecutionException.class);
    }

    @Test
    void 권한이_없는_사람의_구독은_거절되고_메시지를_받지_못한다() throws Exception {
        StompSession session = connect(stranger);
        BlockingQueue<Map<String, Object>> toStranger = subscribe(session, events());

        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card.toString(), "x", 2, "y", 2)));

        assertThat(toStranger.poll(1, TimeUnit.SECONDS)).isNull();
        awaitDisconnected(session);
    }

    @Test
    void 커서는_같은_캔버스_구독자에게_보낸_사람_ID와_함께_간다() throws Exception {
        StompSession editorSession = connect(editor);
        subscribe(editorSession, cursors());
        BlockingQueue<Map<String, Object>> toViewer = subscribe(connect(viewer), cursors());
        awaitSubscribers(cursors(), 2);

        editorSession.send("/app/canvases/" + canvasId + "/cursor", Map.of("x", 512.5, "y", 230.0));

        Map<String, Object> cursor = toViewer.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(cursor).isNotNull();
        assertThat(cursor.get("user")).isEqualTo(Map.of("id", editor.intValue()));
        assertThat(cursor.get("x")).isEqualTo(512.5);
    }

    @Test
    void 구독하지_않은_캔버스로_보낸_커서는_버려지고_연결은_유지된다() throws Exception {
        BlockingQueue<Map<String, Object>> toViewer = subscribe(connect(viewer), cursors());
        awaitSubscribers(cursors(), 1);
        StompSession strangerSession = connect(stranger);  // 권한도 구독도 없음

        strangerSession.send("/app/canvases/" + canvasId + "/cursor", Map.of("x", 1.0, "y", 1.0));

        assertThat(toViewer.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(strangerSession.isConnected()).isTrue();
    }

    @Test
    void 공유가_풀리면_개인_알림을_받고_이후_변경은_받지_못한다() throws Exception {
        StompSession editorSession = connect(editor);
        BlockingQueue<Map<String, Object>> notices = subscribe(editorSession, "/user/queue/canvases");
        BlockingQueue<Map<String, Object>> toEditor = subscribe(editorSession, events());
        awaitSubscribers(events(), 1);
        awaitUserQueue(editor);

        canvasService.replaceShares(canvasId, owner, List.of(new ShareMember(viewer, CanvasRole.VIEW)));

        Map<String, Object> notice = notices.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(notice).isEqualTo(Map.of("type", "revoked", "canvasId", canvasId.intValue()));
        toEditor.clear();  // 공유 변경 자체의 이벤트는 권한이 남아 있던 순간에 갔을 수 있다
        eventService.apply(canvasId, owner, batch(event("move_card", "cardId", card.toString(), "x", 3, "y", 3)));
        assertThat(toEditor.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void 접속자는_들어옴_나감을_알리고_처음_구독하면_지금_목록을_받는다() throws Exception {
        BlockingQueue<Map<String, Object>> presence = subscribe(connect(owner), presence());
        awaitSubscribers(presence(), 1);

        StompSession editorSession = connect(editor);
        subscribe(editorSession, presence());
        Map<String, Object> joined = presence.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(joined).isEqualTo(Map.of("type", "join", "user", Map.of("id", editor.intValue())));

        BlockingQueue<Map<String, Object>> snapshot = subscribe(connect(viewer), "/app/canvases/" + canvasId + "/presence");
        Map<String, Object> users = snapshot.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(users).isNotNull();
        assertThat((List<Object>) users.get("users"))
                .contains(Map.of("id", owner.intValue()), Map.of("id", editor.intValue()));

        editorSession.disconnect();
        Map<String, Object> left = presence.poll(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(left).isEqualTo(Map.of("type", "leave", "user", Map.of("id", editor.intValue())));
    }

    // ---- 도우미 ----

    private String events() {
        return "/topic/canvases/" + canvasId + "/events";
    }

    private String cursors() {
        return "/topic/canvases/" + canvasId + "/cursors";
    }

    private String presence() {
        return "/topic/canvases/" + canvasId + "/presence";
    }

    private StompSession connect(Long userId) throws Exception {
        StompSession session = connectWith("Bearer user-" + userId);
        sessions.add(session);
        return session;
    }

    private StompSession connectWith(String authorization) throws Exception {
        StompHeaders headers = new StompHeaders();
        if (authorization != null) {
            headers.add("Authorization", authorization);
        }
        return client.connectAsync("ws://localhost:" + port + "/api/v1/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() {
                }).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return LinkedHashMap.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });
        return queue;
    }

    /** 브로커에 이 주소의 구독이 count개 이상 등록될 때까지 기다린다(시간이 아니라 상태로 판단). */
    private void awaitSubscribers(String destination, int count) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (subscriberCount(destination) >= count) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("구독이 등록되지 않았습니다: " + destination);
    }

    private void awaitUserQueue(Long userId) throws InterruptedException {
        awaitSubscribers("/queue/canvases-user" + sessionIdOf(userId), 1);
    }

    /** 개인 주소는 브로커에서 세션별 주소로 바뀌므로 그 사람의 세션 ID를 찾는다. */
    private String sessionIdOf(Long userId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var found = broker.getSubscriptionRegistry().findSubscriptions(
                    message("/topic/canvases/" + canvasId + "/events"));
            for (String sessionId : found.keySet()) {
                if (subscriberCount("/queue/canvases-user" + sessionId) > 0) {
                    return sessionId;
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("개인 알림 구독을 찾지 못했습니다: " + userId);
    }

    private int subscriberCount(String destination) {
        return broker.getSubscriptionRegistry().findSubscriptions(message(destination)).size();
    }

    private static org.springframework.messaging.Message<byte[]> message(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static void awaitDisconnected(StompSession session) throws InterruptedException {
        for (int i = 0; i < 100 && session.isConnected(); i++) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(session.isConnected()).isFalse();
    }

    private Long user() {
        String no = "rt-" + UUID.randomUUID();
        return jdbc.queryForObject(
                "insert into app.users (login_id, password_hash, name, role) values (?, 'x', ?, 'USER') returning id",
                Long.class, no, no);
    }

    private static CanvasEventsRequest batch(Map<String, Object> event) {
        return new CanvasEventsRequest(UUID.randomUUID(), List.of(event));
    }

    private static Map<String, Object> event(String type, Object... kv) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        for (int i = 0; i < kv.length; i += 2) {
            e.put((String) kv[i], kv[i + 1]);
        }
        return e;
    }
}
