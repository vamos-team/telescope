package com.telescope.backend.canvas.dto;

import java.util.List;

/**
 * 공유 명단. 소유자는 {@code ownerId}·{@code owner}로만 나오고 {@code members}에는 없다.
 *
 * @param owner 소유자의 이름·부서(#149). {@code ownerId}와 같은 사람
 */
public record CanvasSharesResponse(Long ownerId, UserRef owner, List<ShareMemberResponse> members) {
}
