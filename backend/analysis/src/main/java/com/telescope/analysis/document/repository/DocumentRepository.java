package com.telescope.analysis.document.repository;

import com.telescope.analysis.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

/** 문서 저장소. */
public interface DocumentRepository extends JpaRepository<Document, Long> {
}
