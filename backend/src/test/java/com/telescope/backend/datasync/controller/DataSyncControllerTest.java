package com.telescope.backend.datasync.controller;

import com.telescope.backend.datasync.dto.DataSyncRunResponse;
import com.telescope.backend.datasync.entity.DataSyncRun;
import com.telescope.backend.datasync.service.DataSyncService;
import com.telescope.backend.global.auth.JwtTokenProvider;
import com.telescope.backend.global.auth.SecurityConfig;
import com.telescope.backend.global.auth.SecurityContextCurrentUserProvider;
import com.telescope.backend.global.config.ClockConfig;
import com.telescope.backend.global.exception.GlobalExceptionHandler;
import com.telescope.backend.job.entity.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 전용 적재 API. 실제 보안 설정(JWT, 역할)으로 확인하고 서비스는 목으로 둔다. */
@WebMvcTest(controllers = DataSyncController.class)
@Import({SecurityConfig.class, JwtTokenProvider.class, SecurityContextCurrentUserProvider.class, ClockConfig.class,
        GlobalExceptionHandler.class})
class DataSyncControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean DataSyncService service;

    DataSyncRunResponse response(JobStatus status) {
        return new DataSyncRunResponse(5L, "dart_xbrl", 1L, null, 11L, status, null, null, null, null, false);
    }

    String admin() {
        return "Bearer " + tokens.issue(1L, "ADMIN").accessToken();
    }

    @Test
    void 관리자가_시작하면_202와_실행을_돌려준다() throws Exception {
        DataSyncRun run = org.mockito.Mockito.mock(DataSyncRun.class);
        when(service.start(1L)).thenReturn(new DataSyncService.StartResult(run, true));
        when(service.describe(run)).thenReturn(response(JobStatus.QUEUED));

        mvc.perform(post("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.jobId").value(11));
    }

    @Test
    void 이미_진행_중이면_200과_그_실행을_돌려준다() throws Exception {
        DataSyncRun run = org.mockito.Mockito.mock(DataSyncRun.class);
        when(service.start(1L)).thenReturn(new DataSyncService.StartResult(run, false));
        when(service.describe(run)).thenReturn(response(JobStatus.RUNNING));

        mvc.perform(post("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    void 관리자가_아니면_403_A004이고_시작하지_않는다() throws Exception {
        String user = "Bearer " + tokens.issue(7L, "USER").accessToken();

        mvc.perform(post("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, user))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("A004"));
        mvc.perform(get("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, user))
                .andExpect(status().isForbidden());
        verify(service, never()).start(any());
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(post("/api/v1/data-sync-runs")).andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자는_최근_실행을_본다() throws Exception {
        when(service.recentRuns()).thenReturn(List.of(response(JobStatus.SUCCEEDED)));

        mvc.perform(get("/api/v1/data-sync-runs").header(HttpHeaders.AUTHORIZATION, admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCEEDED"));
    }
}
