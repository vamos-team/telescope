package com.telescope.research.infra.python;

/**
 * ai 서비스가 응답을 주며 시작을 거절했다({@code 4xx}/{@code 5xx}, 미등록 작업의 {@code 501} 포함).
 *
 * <p>응답이 왔으므로 ai가 작업을 접수하지 않은 것이 확실하다. 반대로 타임아웃·연결 실패처럼 응답을 받지 못한
 * {@link AiCallFailedException}은 ai가 접수했는지 알 수 없다. 같은 요청을 다시 보내도 되는 작업(데이터 적재, #135)은
 * 이 둘을 구분해, 거절만 실패로 확정하고 나머지는 다시 보낸다.
 */
public class AiCallRejectedException extends AiCallFailedException {

    /** 사용자에게 보여도 되는 사유와 원인 예외를 받는다. */
    public AiCallRejectedException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 원인 예외 없이 사유만 받는다(가짜 ai의 거절 등). */
    public AiCallRejectedException(String message) {
        super(message);
    }
}
