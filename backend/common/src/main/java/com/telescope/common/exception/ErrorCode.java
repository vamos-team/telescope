package com.telescope.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "입력값이 올바르지 않습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "C002", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "지원하지 않는 HTTP 메서드입니다."),
    JOB_INVALID_TRANSITION(HttpStatus.CONFLICT, "J001", "허용되지 않는 작업 상태 전이입니다."),
    // 인증·인가 (docs/backend/api-spec.md 2장, #34)
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "A001", "사번 또는 비밀번호가 올바르지 않습니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A002", "인증이 필요합니다."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "A003", "인증이 만료되었습니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A004", "권한이 없습니다."),
    EMPLOYEE_NO_DUPLICATED(HttpStatus.CONFLICT, "A005", "이미 가입된 사번입니다."),
    // 연속 로그인 실패로 잠김(#164). 남은 시간은 Retry-After 헤더(초)로 알린다
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "A006", "로그인을 여러 번 실패해 잠시 잠겼습니다. 잠시 후 다시 시도해 주세요."),
    CANVAS_ACCESS_DENIED(HttpStatus.FORBIDDEN, "K001", "이 캔버스를 볼 권한이 없습니다."),
    CANVAS_EDIT_DENIED(HttpStatus.FORBIDDEN, "K002", "이 캔버스를 수정할 권한이 없습니다."),
    CANVAS_TEXT_CONFLICT(HttpStatus.CONFLICT, "K003", "다른 사람이 먼저 글을 고쳤습니다."),
    // 메모(메모 카드·카드 메모·선 메모)는 단 사람만 고치고 지운다. 소유자도 예외 없음(#170)
    CANVAS_MEMO_NOT_MINE(HttpStatus.FORBIDDEN, "K004", "다른 사람이 단 메모는 고치거나 지울 수 없습니다."),
    DOCUMENT_UPLOAD_REJECTED(HttpStatus.BAD_REQUEST, "D001", "올릴 수 없는 파일입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    public static ErrorCode from(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> RESOURCE_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            default -> status.is4xxClientError() ? INVALID_INPUT : INTERNAL_SERVER_ERROR;
        };
    }
}