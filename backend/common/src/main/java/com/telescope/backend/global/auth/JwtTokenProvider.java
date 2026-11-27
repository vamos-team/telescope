package com.telescope.backend.global.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 액세스 토큰(JWT, HS256)을 발급하고 검증한다. HTTP 필터({@link JwtAuthenticationFilter})와
 * STOMP 연결 인증({@link StompAuthChannelInterceptor})이 이 검증 로직 하나를 같이 쓴다.
 *
 * <p>토큰: {@code sub}=사용자 ID, {@code role}=역할, {@code iat}·{@code exp}. 역할은 발급 시점 값이라
 * 역할이 바뀌어도 기존 토큰이 만료될 때까지는 예전 역할로 동작한다(유효 기간이 짧아 MVP에서는 받아들인다).
 */
@Component
@EnableConfigurationProperties(JwtProperties.class)
public class JwtTokenProvider implements AccessTokenVerifier {

    static final String ROLE_CLAIM = "role";
    private static final String EXPIRED = "token_expired";

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration expiry;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        SecretKey key = new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // 기본 검증기(60초 허용 오차)를 쓰지 않는다. 발급과 검증을 같은 서버가 하므로 오차 없이 만료를 본다.
        // 만료를 별도 오류 코드로 남겨 A003과 A002(위조 등)를 구분한다.
        nimbusDecoder.setJwtValidator(jwt -> jwt.getExpiresAt() != null && clock.instant().isBefore(jwt.getExpiresAt())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(EXPIRED)));
        this.decoder = nimbusDecoder;
        this.expiry = properties.accessTokenExpiry();
        this.clock = clock;
    }

    /** 사용자 ID와 역할을 담은 액세스 토큰을 발급한다. */
    public IssuedToken issue(Long userId, String role) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .claim(ROLE_CLAIM, role)
                .issuedAt(now)
                .expiresAt(now.plus(expiry))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
        return new IssuedToken(token, expiry.toSeconds());
    }

    /**
     * 토큰을 검증하고 사용자와 역할을 돌려준다.
     *
     * @throws BusinessException 만료면 {@code A003}, 형식 오류·서명 위조·필수 값 누락이면 {@code A002}
     */
    public AuthenticatedUser authenticate(String token) {
        Jwt jwt = decode(token);
        String role = jwt.getClaimAsString(ROLE_CLAIM);
        if (role == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return new AuthenticatedUser(Long.valueOf(jwt.getSubject()), role);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }

    /** STOMP 연결 인증용. 사용자 ID만 돌려준다. 예외는 {@link #authenticate(String)}와 같다. */
    @Override
    public Long verify(String token) {
        return authenticate(token).id();
    }

    private Jwt decode(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        try {
            return decoder.decode(token);
        } catch (JwtValidationException e) {
            // 서명이 맞는 토큰에만 검증기가 돈다. 위조 토큰은 만료 여부와 관계없이 A002다.
            boolean expired = e.getErrors().stream().anyMatch(error -> EXPIRED.equals(error.getErrorCode()));
            throw new BusinessException(expired ? ErrorCode.TOKEN_EXPIRED : ErrorCode.UNAUTHENTICATED);
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
