package com.telescope.canvas.canvas.controller;

import com.telescope.canvas.canvas.dto.CanvasActivityResponse;
import com.telescope.canvas.canvas.dto.CanvasCreateRequest;
import com.telescope.canvas.canvas.dto.CanvasDetailResponse;
import com.telescope.canvas.canvas.dto.CanvasEventsRequest;
import com.telescope.canvas.canvas.dto.CanvasEventsResponse;
import com.telescope.canvas.canvas.dto.CanvasRenameRequest;
import com.telescope.canvas.canvas.dto.CanvasSharesResponse;
import com.telescope.canvas.canvas.dto.CanvasSharesUpdateRequest;
import com.telescope.canvas.canvas.dto.CanvasSummaryResponse;
import com.telescope.canvas.canvas.service.CanvasEventService;
import com.telescope.canvas.canvas.service.CanvasService;
import com.telescope.common.auth.CurrentUserProvider;
import com.telescope.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 협업 캔버스 API. 경로·필드는 {@code docs/frontend/api-spec.md} 3장, 규약은 {@code docs/backend/api-spec.md} 4·5장.
 * 사용자는 인증 결과({@link CurrentUserProvider})에서만 가져오고 요청 값을 쓰지 않는다.
 */
@RestController
@RequestMapping("/api/v1/canvases")
@RequiredArgsConstructor
public class CanvasController {

    private final CanvasService canvasService;
    private final CanvasEventService eventService;
    private final CurrentUserProvider currentUser;

    /** 내가 볼 수 있는 캔버스 목록. {@code filter}: all(기본) / mine / shared. */
    @GetMapping
    public ApiResponse<List<CanvasSummaryResponse>> list(@RequestParam(defaultValue = "all") String filter) {
        return ApiResponse.success(canvasService.list(currentUser.currentUserId(), filter));
    }

    /** 새 캔버스를 만든다. 만든 사람이 소유자가 된다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CanvasSummaryResponse> create(@Valid @RequestBody CanvasCreateRequest request) {
        return ApiResponse.success(canvasService.create(currentUser.currentUserId(), request));
    }

    /** 캔버스 이름을 바꾼다(소유자·편집). */
    @PatchMapping("/{id}")
    public ApiResponse<CanvasSummaryResponse> rename(@PathVariable Long id, @Valid @RequestBody CanvasRenameRequest request) {
        return ApiResponse.success(canvasService.rename(id, currentUser.currentUserId(), request.name()));
    }

    /** 캔버스를 지운다(소유자만). 본문 없이 204. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        canvasService.delete(id, currentUser.currentUserId());
        return ResponseEntity.noContent().build();
    }

    /** 캔버스를 연다(권한자 모두): 카드·연결·마지막 이벤트 번호. */
    @GetMapping("/{id}")
    public ApiResponse<CanvasDetailResponse> open(@PathVariable Long id) {
        return ApiResponse.success(canvasService.open(id, currentUser.currentUserId()));
    }

    /** 카드·연결 조작 묶음을 저장한다(소유자·편집). */
    @PostMapping("/{id}/events")
    public ApiResponse<CanvasEventsResponse> events(@PathVariable Long id, @Valid @RequestBody CanvasEventsRequest request) {
        return ApiResponse.success(eventService.apply(id, currentUser.currentUserId(), request));
    }

    /** 활동 로그(권한자 모두). 최근 순 200건. */
    @GetMapping("/{id}/activities")
    public ApiResponse<List<CanvasActivityResponse>> activities(@PathVariable Long id) {
        return ApiResponse.success(canvasService.activities(id, currentUser.currentUserId()));
    }

    /** 공유 명단(권한자 모두). */
    @GetMapping("/{id}/shares")
    public ApiResponse<CanvasSharesResponse> shares(@PathVariable Long id) {
        return ApiResponse.success(canvasService.shares(id, currentUser.currentUserId()));
    }

    /** 공유 명단을 통째로 바꾼다(소유자만). */
    @PutMapping("/{id}/shares")
    public ApiResponse<CanvasSharesResponse> replaceShares(@PathVariable Long id,
                                                           @Valid @RequestBody CanvasSharesUpdateRequest request) {
        return ApiResponse.success(canvasService.replaceShares(id, currentUser.currentUserId(), request.members()));
    }
}
