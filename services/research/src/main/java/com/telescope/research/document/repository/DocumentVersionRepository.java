package com.telescope.research.document.repository;

import com.telescope.research.document.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 문서 버전 저장소. */
public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    /** 자료 준비 작업에 연결된 문서 버전. 작업 하나에 버전 하나다({@code uk_document_version_job}). */
    Optional<DocumentVersion> findByJobId(Long jobId);

    /** 문서의 가장 최근 버전. */
    Optional<DocumentVersion> findFirstByDocumentIdOrderByVersionNoDesc(Long documentId);
}
