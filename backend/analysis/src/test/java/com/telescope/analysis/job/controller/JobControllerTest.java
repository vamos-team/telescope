package com.telescope.analysis.job.controller;

import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.common.exception.GlobalExceptionHandler;
import com.telescope.analysis.job.dto.JobResponse;
import com.telescope.analysis.job.entity.JobStatus;
import com.telescope.analysis.job.entity.JobType;
import com.telescope.analysis.job.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 필터 없이 컨트롤러 응답만 본다. 이 경로는 공개 경로가 아니라 로그인이 필요하고({@code 401}, 보안 설정),
 * 경로별 {@code 401}은 캔버스·문서·적재 컨트롤러 테스트에서 확인한다.
 */
class JobControllerTest {

    JobService jobService = Mockito.mock(JobService.class);
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new JobController(jobService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 작업이_있으면_상태를_ApiResponse로_감싸_반환한다() throws Exception {
        when(jobService.getJob(42L)).thenReturn(new JobResponse(
                42L, JobType.ANALYSIS, JobStatus.RUNNING,
                LocalDateTime.of(2026, 11, 16, 9, 0), null, null));

        mockMvc.perform(get("/api/v1/jobs/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error").value(nullValue()))
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.jobType").value("ANALYSIS"))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.finishedAt").value(nullValue()));
    }

    @Test
    void 없는_작업이면_404와_C002를_반환한다() throws Exception {
        when(jobService.getJob(7L))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ID 7번 작업을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/v1/jobs/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("C002"));
    }
}
