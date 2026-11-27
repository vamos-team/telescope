package com.telescope.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 실패 응답의 {@code data}에 함께 담을 값. 화면이 다시 그릴 정보가 필요할 때만 쓴다(예: 글 충돌 시 현재 글). */
    private final transient Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String detailMessage) {
        this(errorCode, detailMessage, null);
    }

    /**
     * 실패 사유와 함께 화면에 돌려줄 데이터를 담는다.
     *
     * @param data 응답 {@code data}에 그대로 담긴다. 내부 정보·문서 본문은 넣지 않는다
     */
    public BusinessException(ErrorCode errorCode, String detailMessage, Object data) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.data = data;
    }
}
