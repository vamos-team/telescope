package com.telescope.backend.global.auth;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** STOMP 연결 인증. STOMP 1.2의 {@code STOMP} 명령도 {@code CONNECT}와 같게 다룬다. */
class StompAuthChannelInterceptorTest {

    StompAuthChannelInterceptor interceptor = new StompAuthChannelInterceptor(token -> {
        if (token.equals("good")) {
            return 7L;
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    });

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 연결_명령은_토큰을_검증해_사용자를_붙인다(StompCommand command) {
        Message<?> result = interceptor.preSend(connect(command, "Bearer good"), null);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(result, StompHeaderAccessor.class);
        assertThat(accessor.getUser()).isEqualTo(new UserPrincipal(7L));
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void 연결_명령의_토큰이_없거나_틀리면_거절한다(StompCommand command) {
        assertThatThrownBy(() -> interceptor.preSend(connect(command, null), null))
                .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("A002");
        assertThatThrownBy(() -> interceptor.preSend(connect(command, "Bearer bad"), null))
                .isInstanceOf(MessageDeliveryException.class).hasMessageContaining("A002");
    }

    private static Message<byte[]> connect(StompCommand command, String authorization) {
        StompHeaderAccessor a = StompHeaderAccessor.create(command);
        if (authorization != null) {
            a.addNativeHeader("Authorization", authorization);
        }
        a.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], a.getMessageHeaders());
    }
}
