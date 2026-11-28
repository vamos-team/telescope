package com.telescope.canvas.canvas.service;

import com.telescope.canvas.canvas.entity.Canvas;
import com.telescope.canvas.canvas.entity.CanvasRole;
import com.telescope.canvas.canvas.entity.CanvasShare;
import com.telescope.canvas.canvas.repository.CanvasRepository;
import com.telescope.canvas.canvas.repository.CanvasShareRepository;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 캔버스 권한 확인. HTTP API와 실시간 구독(#83)이 같은 규칙을 쓰도록 한곳에 둔다.
 *
 * <p>권한 계산: 소유자({@code canvas.owner_id}) → 직접 초대({@code canvas_share}). 둘 다 아니면 권한 없음.
 * 일반 액세스(회사 전체 공개)는 없다. 규칙표는 {@code docs/backend/api-spec.md} 4장.
 */
@Service
@RequiredArgsConstructor
public class CanvasAccessService {

    private final CanvasRepository canvasRepository;
    private final CanvasShareRepository shareRepository;

    /** 이 사람이 이 캔버스에서 가진 권한. 권한이 없으면 비어 있다. */
    @Transactional(readOnly = true)
    public Optional<CanvasRole> roleOf(Canvas canvas, Long userId) {
        if (canvas.getOwnerId().equals(userId)) {
            return Optional.of(CanvasRole.OWNER);
        }
        return shareRepository.findByCanvasIdAndUserId(canvas.getId(), userId).map(CanvasShare::getRole);
    }

    /**
     * 실시간 구독(#83)용: 캔버스 ID로 권한을 구한다. 없거나 삭제된 캔버스면 권한 없음으로 본다.
     */
    @Transactional(readOnly = true)
    public Optional<CanvasRole> roleOf(Long canvasId, Long userId) {
        return canvasRepository.findById(canvasId)
                .filter(c -> !c.isDeleted())
                .flatMap(c -> roleOf(c, userId));
    }

    /** 삭제되지 않은 캔버스를 읽는다. 없거나 삭제됐으면 {@code 404 C002}. */
    @Transactional(readOnly = true)
    public Canvas loadActive(Long canvasId) {
        return canvasRepository.findById(canvasId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> notFound(canvasId));
    }

    /**
     * 삭제되지 않은 캔버스를 행을 잠그고 읽는다. 이벤트 저장·공유 변경처럼 이벤트 번호를 쓰는 작업에서
     * 같은 캔버스 요청을 한 줄로 세운다. 호출하는 쪽 트랜잭션 안에서 불러야 한다.
     */
    public Canvas loadActiveForUpdate(Long canvasId) {
        return canvasRepository.findByIdForUpdate(canvasId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> notFound(canvasId));
    }

    /** 볼 수 있는지 확인하고 권한을 돌려준다. 권한이 없으면 {@code 403 K001}. */
    public CanvasRole requireView(Canvas canvas, Long userId) {
        return roleOf(canvas, userId).orElseThrow(() -> new BusinessException(ErrorCode.CANVAS_ACCESS_DENIED));
    }

    /** 카드·연결을 고칠 수 있는지(소유자·편집) 확인한다. 보기 권한이면 {@code 403 K002}. */
    public CanvasRole requireEdit(Canvas canvas, Long userId) {
        CanvasRole role = requireView(canvas, userId);
        if (!role.canEdit()) {
            throw new BusinessException(ErrorCode.CANVAS_EDIT_DENIED);
        }
        return role;
    }

    /** 소유자인지 확인한다(삭제, 공유 설정 변경). 소유자가 아니면 {@code 403 K002}. */
    public void requireOwner(Canvas canvas, Long userId) {
        CanvasRole role = requireView(canvas, userId);
        if (role != CanvasRole.OWNER) {
            throw new BusinessException(ErrorCode.CANVAS_EDIT_DENIED, "소유자만 할 수 있습니다.");
        }
    }

    private static BusinessException notFound(Long canvasId) {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ID " + canvasId + "번 캔버스를 찾을 수 없습니다.");
    }
}
