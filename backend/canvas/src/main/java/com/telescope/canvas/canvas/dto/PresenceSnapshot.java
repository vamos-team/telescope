package com.telescope.canvas.canvas.dto;

import java.util.List;

/** 처음 구독할 때 한 번 받는 지금 접속자 목록(`/app/canvases/{id}/presence`). */
public record PresenceSnapshot(List<UserRef> users) {
}
