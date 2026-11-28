package com.telescope.research.document.repository;

import com.telescope.research.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

/** 문서 저장소. */
public interface DocumentRepository extends JpaRepository<Document, Long> {
}
