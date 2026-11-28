package com.telescope.research.document.service;

public interface CanvasAccessClient {

    void requireView(Long canvasId, Long userId);

    void requireEdit(Long canvasId, Long userId);
}
