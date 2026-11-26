package com.telescope.backend.global.auth;

import com.telescope.backend.global.exception.ErrorCode;
import com.telescope.backend.global.response.ApiResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * HTTP 인증·인가 설정. 세션 없이 요청마다 JWT로 인증한다.
 *
 * <ul>
 *   <li>공개: {@code /actuator/health}, {@code POST /api/v1/auth/login}, WebSocket 연결 주소 {@code /api/v1/ws}.
 *       WebSocket은 브라우저가 연결 요청에 헤더를 붙일 수 없어 STOMP {@code CONNECT}에서 인증한다
 *       ({@link StompAuthChannelInterceptor}, docs/backend/canvas.md 6장).</li>
 *   <li>그 밖의 경로는 인증이 필요하다. 관리자 전용 기능은 메서드에 {@code @PreAuthorize("hasRole('ADMIN')")}를 단다.</li>
 *   <li>실패 응답은 {@code ApiResponse} 형식: 인증 없음·위조 401 {@code A002}, 만료 401 {@code A003}, 권한 부족 403 {@code A004}.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenProvider tokenProvider) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 컨트롤러 밖 오류를 Boot 오류 처리로 넘길 때 401로 덮이지 않게 한다
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/ws").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> {
                            Object error = request.getAttribute(JwtAuthenticationFilter.AUTH_ERROR_ATTRIBUTE);
                            write(response, error instanceof ErrorCode code ? code : ErrorCode.UNAUTHENTICATED);
                        })
                        .accessDeniedHandler((request, response, e) -> write(response, ErrorCode.ACCESS_DENIED)))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /** 비밀번호 해시. 평문은 저장하지 않는다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static void write(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        MAPPER.writeValue(response.getOutputStream(), ApiResponse.fail(code));
    }
}
