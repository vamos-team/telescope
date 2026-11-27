package com.telescope.backend.canvas.dto;

/**
 * 화면이 보내는 내 커서 위치(`/app/canvases/{id}/cursor`). 캔버스 좌표다(확대·이동과 무관).
 */
public record CursorPosition(Double x, Double y) {

    /** 두 값이 모두 있고 유한한 숫자인지. 아니면 버린다. */
    public boolean isValid() {
        return x != null && y != null && Double.isFinite(x) && Double.isFinite(y);
    }
}
