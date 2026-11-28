package com.telescope.common.auth;

import com.telescope.common.exception.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 키가 설정되지 않았으면 모든 내부 호출을 거절한다. */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    static final String PATH_PREFIX = "/internal/";

    private final byte[] expected;
    private final ErrorWriter writer;

    public InternalApiKeyFilter(String apiKey, ErrorWriter writer) {
        this.expected = apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8);
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        boolean ok = expected.length > 0 && given != null
                && MessageDigest.isEqual(expected, given.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            writer.write(response, ErrorCode.UNAUTHENTICATED);
            return;
        }
        chain.doFilter(request, response);
    }

    @FunctionalInterface
    public interface ErrorWriter {
        void write(HttpServletResponse response, ErrorCode code) throws IOException;
    }
}
