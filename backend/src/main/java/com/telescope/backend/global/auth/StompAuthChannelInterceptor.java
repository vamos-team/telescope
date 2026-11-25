package com.telescope.backend.global.auth;

import com.telescope.backend.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * STOMP 연결 인증. {@code CONNECT} 프레임의 {@code Authorization: Bearer {token}} 헤더로 사용자를 정하고 세션에 붙인다.
 * 토큰을 주소(쿼리)로 받지 않는다(로그 노출 방지). 연결 뒤의 프레임은 인증된 사용자가 있어야만 통과한다.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private static final String BEARER = "Bearer ";

    private final AccessTokenVerifier verifier;

    /**
     * 들어오는 프레임을 인증한다. {@code CONNECT}면 토큰을 검증해 사용자를 붙이고, 그 밖의 프레임은 사용자가 없으면 막는다.
     *
     * @throws MessageDeliveryException 인증 실패. 화면은 STOMP {@code ERROR} 프레임을 받는다
     */
    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (accessor.getCommand() == StompCommand.CONNECT) {
            accessor.setUser(new UserPrincipal(verify(accessor.getFirstNativeHeader("Authorization"))));
            return message;
        }
        if (accessor.getCommand() != StompCommand.DISCONNECT && !(accessor.getUser() instanceof UserPrincipal)) {
            throw new MessageDeliveryException("A002 인증이 필요합니다.");
        }
        return message;
    }

    private Long verify(String header) {
        if (header == null || !header.startsWith(BEARER)) {
            throw new MessageDeliveryException("A002 인증이 필요합니다.");
        }
        try {
            return verifier.verify(header.substring(BEARER.length()).trim());
        } catch (BusinessException e) {
            throw new MessageDeliveryException(e.getErrorCode().getCode() + " " + e.getMessage());
        }
    }
}
