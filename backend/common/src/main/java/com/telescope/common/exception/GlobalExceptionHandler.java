package com.telescope.common.exception;

import com.telescope.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        if (errorCode.getStatus().is5xxServerError()) {
            log.error("BusinessException: {} - {}", errorCode.getCode(), e.getMessage(), e);
        } else {
            log.warn("BusinessException: {} - {}", errorCode.getCode(), e.getMessage());
        }
        ApiResponse<?> body = e.getData() == null
                ? ApiResponse.fail(errorCode, e.getMessage())
                : ApiResponse.fail(errorCode, e.getMessage(), e.getData());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(errorCode.getStatus());
        if (e instanceof RetryAfterException retry) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retry.getRetryAfterSeconds()));
        }
        return response.body(body);
    }

    /**
     * 메서드 보안({@code @PreAuthorize})의 거절. 컨트롤러 안에서 던져지므로 보안 필터의 403 처리 대신 여기서 받는다.
     * 받지 않으면 아래 {@link #handleUnexpectedException}이 500으로 바꾼다.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;
        return ResponseEntity.status(errorCode.getStatus()).body(ApiResponse.fail(errorCode));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception e) {
        log.error("Unexpected exception", e);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.fail(errorCode));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        if (statusCode.is5xxServerError()) {
            log.error("Spring MVC exception", ex);
        }
        ErrorCode errorCode = ErrorCode.from(statusCode);
        return super.handleExceptionInternal(ex, ApiResponse.fail(errorCode), headers, statusCode, request);
    }

    /** 설정한 업로드 크기 상한(예: {@code 50MB}). 서블릿 컨테이너가 예외에 상한을 담지 않을 때 문구에 쓴다. */
    private String maxFileSize = "";

    /** 업로드 크기 상한 설정을 받는다. 설정이 없으면 문구에서 상한을 뺀다. */
    @Autowired(required = false)
    public void setMaxFileSize(@Value("${spring.servlet.multipart.max-file-size:}") String maxFileSize) {
        this.maxFileSize = maxFileSize == null ? "" : maxFileSize;
    }

    /**
     * 업로드 크기 상한({@code spring.servlet.multipart.max-file-size})을 넘었을 때. 상한 값을 문구에 담아 {@code 413}으로 답한다.
     * Tomcat은 예외에 상한을 담지 않으므로(-1) 설정 값을 쓴다.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String limit = ex.getMaxUploadSize() > 0 ? " (최대 " + (ex.getMaxUploadSize() / (1024 * 1024)) + "MB)"
                : maxFileSize.isBlank() ? "" : " (최대 " + maxFileSize + ")";
        return ResponseEntity
                .status(status)
                .headers(headers)
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT, "파일이 너무 큽니다" + limit + "."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.INVALID_INPUT.getMessage());

        return ResponseEntity
                .status(status)
                .headers(headers)
                .body(ApiResponse.fail(ErrorCode.INVALID_INPUT, message));
    }
}