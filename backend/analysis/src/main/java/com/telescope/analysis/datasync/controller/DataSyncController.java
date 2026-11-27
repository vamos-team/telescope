package com.telescope.analysis.datasync.controller;

import com.telescope.analysis.datasync.dto.DataSyncRunResponse;
import com.telescope.analysis.datasync.service.DataSyncService;
import com.telescope.common.auth.CurrentUserProvider;
import com.telescope.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 재무 데이터 적재 실행(관리자 전용). 규약은 {@code docs/backend/api-spec.md}의 재무 데이터 적재 절.
 */
@RestController
@RequestMapping("/api/v1/data-sync-runs")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class DataSyncController {

    private final DataSyncService dataSyncService;
    private final CurrentUserProvider currentUser;

    /**
     * 적재를 바로 시작한다. 새로 시작하면 {@code 202}, 이미 진행 중이면 그 실행을 {@code 200}으로 돌려준다.
     * 진행 상황은 {@code GET /jobs/{jobId}} 또는 이 목록으로 본다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<DataSyncRunResponse>> start() {
        DataSyncService.StartResult result = dataSyncService.start(currentUser.currentUserId());
        return ResponseEntity.status(result.started() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResponse.success(dataSyncService.describe(result.run())));
    }

    /** 최근 실행 20건. */
    @GetMapping
    public ApiResponse<List<DataSyncRunResponse>> recent() {
        return ApiResponse.success(dataSyncService.recentRuns());
    }
}
