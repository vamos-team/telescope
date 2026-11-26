package com.telescope.backend.document.repository;

import com.telescope.backend.document.entity.DocumentPreparationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 자료 준비 결과 저장소. */
public interface DocumentPreparationResultRepository extends JpaRepository<DocumentPreparationResult, Long> {

    /** 문서 버전의 결과. 아직 준비가 끝나지 않았으면 없다. */
    Optional<DocumentPreparationResult> findByDocumentVersionId(Long documentVersionId);
}
