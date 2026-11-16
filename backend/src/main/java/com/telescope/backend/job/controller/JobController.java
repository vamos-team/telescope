package com.telescope.backend.job.controller;

import com.telescope.backend.global.response.ApiResponse;
import com.telescope.backend.job.dto.JobResponse;
import com.telescope.backend.job.service.JobService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    /**
     * MVP: 로그인한 사용자라면 누구나 어떤 작업이든 조회할 수 있다. Job에 소유자 정보가 없어서
     * 본인 작업만 보게 제한하지 못한다. 대상 도메인이 생기면 대상의 소유자 기준으로 좁힌다.
     * 인증은 user 이슈에서 Security가 붙은 뒤 적용되므로 지금은 경로가 열려 있다.
     */
    @GetMapping("/{id}")
    public ApiResponse<JobResponse> getJob(@PathVariable Long id) {
        return ApiResponse.success(jobService.getJob(id));
    }
}
