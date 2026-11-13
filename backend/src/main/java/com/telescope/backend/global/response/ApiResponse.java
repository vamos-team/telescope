package com.telescope.backend.global.response;

import com.telescope.backend.global.exception.ErrorCode;
import lombok.Getter;

@Getter
public class ApiResponse<T> {

    private final T data;
    private final ErrorResponse error;

    private ApiResponse(T data, ErrorResponse error) {
        this.data = data;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null);
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode) {
        return new ApiResponse<>(null, new ErrorResponse(errorCode.getCode(), errorCode.getMessage()));
    }

    public static ApiResponse<Void> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(null, new ErrorResponse(errorCode.getCode(), message));
    }
}