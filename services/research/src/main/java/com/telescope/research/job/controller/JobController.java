package com.telescope.research.job.controller;

import com.telescope.common.response.ApiResponse;
import com.telescope.research.job.dto.JobResponse;
import com.telescope.research.job.service.JobService;
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
     * 본인 작업만 보게 제한하지 못한다. 대상 도메인 기준으로 좁힐지는 #151에서 정한다.
     * 이 경로는 로그인이 필요하다. 보안 설정의 공개 경로(로그인·회원가입·헬스 체크·WebSocket 연결)가 아니어서
     * 토큰이 없거나 틀리면 {@code 401}이다(#34).
     */
    @GetMapping("/{id}")
    public ApiResponse<JobResponse> getJob(@PathVariable Long id) {
        return ApiResponse.success(jobService.getJob(id));
    }
}
