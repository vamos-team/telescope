package com.telescope.backend.canvas.controller;

import com.telescope.backend.canvas.dto.UserRef;
import com.telescope.backend.canvas.entity.CanvasRole;
import com.telescope.backend.canvas.service.CanvasAccessService;
import com.telescope.backend.canvas.service.CanvasPeople;
import com.telescope.backend.canvas.service.CanvasRealtimeNotifier;
import com.telescope.backend.canvas.service.CanvasRealtimeRegistry;
import com.telescope.backend.global.auth.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 구독·커서 보내기 권한 확인의 순서와 경계 조건. 권한 회수와 구독이 겹치는 경합을 순서를 정해 재현한다. */
class CanvasStompInterceptorTest {

    private static final Long CANVAS = 1L;
    private static final Long USER = 7L;
    private static final UserRef PERSON = new UserRef(USER, "이재무", "네트워크센터");

    CanvasAccessService access = mock(CanvasAccessService.class);
    CanvasRealtimeNotifier notifier = mock(CanvasRealtimeNotifier.class);
    CanvasPeople people = mock(CanvasPeople.class);
    CanvasRealtimeRegistry registry = new CanvasRealtimeRegistry();
    CanvasStompInterceptor interceptor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<CanvasRealtimeNotifier> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(notifier);
        when(people.ref(USER)).thenReturn(PERSON);
        interceptor = new CanvasStompInterceptor(access, registry, people, provider);
    }

    @Test
    void 권한을_확인한_뒤_기록하기_전에_공유가_풀리면_기록을_지우고_거절한다() {
        // 첫 확인은 권한 있음, 기록한 뒤의 두 번째 확인은 권한 없음(그 사이 회수가 커밋됨)
        when(access.roleOf(CANVAS, USER)).thenReturn(Optional.of(CanvasRole.EDIT), Optional.empty());

        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/canvases/1/presence"), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("K001");
        assertThat(registry.isSubscribed("s1", CANVAS)).isFalse();
        verify(notifier, never()).presence(anyLong(), anyString(), any(UserRef.class));
    }

    @Test
    void 두_번_모두_권한이_있으면_기록하고_처음_들어온_사람만_들어옴을_알린다() {
        when(access.roleOf(CANVAS, USER)).thenReturn(Optional.of(CanvasRole.VIEW));

        interceptor.preSend(subscribe("/topic/canvases/1/presence"), null);

        assertThat(registry.isSubscribed("s1", CANVAS)).isTrue();
        verify(notifier, times(1)).presence(CANVAS, "join", PERSON);
        assertThat(registry.personOf("s1", CANVAS)).contains(PERSON);  // 커서에 붙일 이름을 구독 때 기록
    }

    @Test
    void 구독하지_않은_캔버스로_보낸_커서는_조용히_버린다() {
        assertThat(interceptor.preSend(send("/app/canvases/1/cursor"), null)).isNull();
        verify(access, never()).roleOf(any(Long.class), any(Long.class));
    }

    @Test
    void 구독한_캔버스의_커서는_50ms보다_잦으면_버린다() {
        when(access.roleOf(CANVAS, USER)).thenReturn(Optional.of(CanvasRole.EDIT));
        interceptor.preSend(subscribe("/topic/canvases/1/cursors"), null);

        assertThat(interceptor.preSend(send("/app/canvases/1/cursor"), null)).isNotNull();
        assertThat(interceptor.preSend(send("/app/canvases/1/cursor"), null)).isNull();
    }

    @Test
    void 알_수_없는_주소의_구독은_거절한다() {
        assertThatThrownBy(() -> interceptor.preSend(subscribe("/topic/other"), null))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("C001");
    }

    private static Message<byte[]> subscribe(String destination) {
        StompHeaderAccessor a = frame(StompCommand.SUBSCRIBE, destination);
        a.setSubscriptionId("sub-1");
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }

    private static Message<byte[]> send(String destination) {
        return MessageBuilder.createMessage("{\"x\":1,\"y\":1}".getBytes(), frame(StompCommand.SEND, destination).getMessageHeaders());
    }

    private static StompHeaderAccessor frame(StompCommand command, String destination) {
        StompHeaderAccessor a = StompHeaderAccessor.create(command);
        a.setDestination(destination);
        a.setSessionId("s1");
        a.setUser(new UserPrincipal(USER));
        a.setLeaveMutable(true);
        return a;
    }
}
