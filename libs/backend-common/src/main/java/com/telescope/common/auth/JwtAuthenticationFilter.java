package com.telescope.common.auth;

import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * {@code Authorization: Bearer {token}}을 검증해 요청의 인증 정보를 채운다.
 *
 * <p>여기서 바로 401을 내지 않는다. 실패 사유(A002/A003)만 요청 속성에 남기고 다음으로 넘겨,
 * 공개 경로(로그인 등)는 만료된 토큰이 붙어 와도 통과하고 보호 경로는 {@link SecurityConfig}의 진입점이 사유대로 답한다.
 * 빈으로 등록하지 않는다(서블릿 필터로 한 번 더 등록되지 않게 {@link SecurityConfig}에서 직접 만든다).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 토큰 검증 실패 사유({@code ErrorCode})를 담는 요청 속성 이름. */
    static final String AUTH_ERROR_ATTRIBUTE = JwtAuthenticationFilter.class.getName() + ".error";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null) {
            authenticate(request, header);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String header) {
        String token = BearerToken.extract(header);
        if (token == null) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, ErrorCode.UNAUTHENTICATED);
            return;
        }
        try {
            AuthenticatedUser user = tokenProvider.authenticate(token);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
            SecurityContextHolder.setContext(context);
        } catch (BusinessException e) {
            request.setAttribute(AUTH_ERROR_ATTRIBUTE, e.getErrorCode());
        }
    }
}
