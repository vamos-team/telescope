package com.telescope.backend.document.service;

/**
 * 문서가 캔버스 권한을 확인하는 창구. 캔버스와 공유 명단은 canvas 쪽이 가지고 있어 문서는 이 인터페이스로만 확인한다.
 * 캔버스가 없거나 지워졌으면 {@code C002}, 권한이 모자라면 {@code K001}·{@code K002}로 거절한다.
 */
public interface CanvasAccessClient {

    void requireView(Long canvasId, Long userId);

    void requireEdit(Long canvasId, Long userId);
}
