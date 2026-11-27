package com.telescope.analysis.document.service;

import com.telescope.analysis.document.entity.DocumentPreparationResult;
import com.telescope.analysis.document.entity.DocumentVersion;
import com.telescope.analysis.document.repository.DocumentPreparationResultRepository;
import com.telescope.analysis.document.repository.DocumentVersionRepository;
import com.telescope.analysis.job.entity.JobType;
import com.telescope.analysis.job.service.JobResultHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 자료 준비 작업이 끝나면 ai 결과를 그 문서 버전의 결과로 저장한다(#63의 연결 지점).
 *
 * <p>{@code JobService}가 작업 행을 잠근 트랜잭션 안에서 부른다. DB 저장만 하고, 실패하면 예외를 던져
 * 완료 확정까지 함께 되돌린다(다음 조회에서 재시도).
 */
@Component
@RequiredArgsConstructor
public class DocumentPreparationResultHandler implements JobResultHandler {

    private final DocumentVersionRepository versionRepository;
    private final DocumentPreparationResultRepository resultRepository;

    /** 자료 준비 작업을 맡는다. */
    @Override
    public JobType jobType() {
        return JobType.DOCUMENT_PREPARATION;
    }

    /**
     * 작업에 연결된 문서 버전을 찾아 결과를 저장한다. 결과가 비어 있으면 빈 객체로 저장한다.
     *
     * @throws IllegalStateException 작업에 연결된 문서 버전이 없을 때
     */
    @Override
    public void saveResult(Long jobId, Object result) {
        DocumentVersion version = versionRepository.findByJobId(jobId)
                .orElseThrow(() -> new IllegalStateException("작업에 연결된 문서 버전이 없습니다: jobId=" + jobId));
        resultRepository.save(DocumentPreparationResult.of(version.getId(),
                DocumentJson.write(result == null ? Map.of() : result)));
    }
}
