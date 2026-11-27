package com.telescope.backend.infra.python;

/**
 * ai 서비스 호출이 실패했을 때 던진다(타임아웃, 연결 실패, {@code 4xx}/{@code 5xx} 응답, 형식이 틀린 응답).
 *
 * <p>상태 조회에서는 일시 오류로 보고 다음 폴링에서 다시 시도한다. 시작 호출에서는 호출한 쪽이 작업을
 * {@code FAILED}로 끝낸다({@code docs/backend/ai-contract.md} 2.5). 없는 작업({@code 404})은
 * {@link AiJobNotFoundException}으로 따로 구분한다.
 *
 * <p>메시지에는 요청·응답 본문과 비밀키를 넣지 않는다. 호출한 쪽이 작업 실패 사유로 그대로 저장할 수 있다.
 */
public class AiCallFailedException extends RuntimeException {

    /** 사용자에게 보여도 되는 사유와 원인 예외를 받는다. */
    public AiCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 원인 예외 없이 사유만 받는다(응답 형식이 틀린 경우 등). */
    public AiCallFailedException(String message) {
        super(message);
    }
}
