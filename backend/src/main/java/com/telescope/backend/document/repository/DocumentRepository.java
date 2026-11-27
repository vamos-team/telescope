package com.telescope.backend.document.repository;

import com.telescope.backend.document.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

/** 문서 저장소. */
public interface DocumentRepository extends JpaRepository<Document, Long> {
}
