package com.telescope.backend.document.entity;

import com.telescope.backend.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문서 한 건(버전과 무관한 정체성). 실제 파일과 발행 정보는 {@link DocumentVersion}에 있다.
 * 스키마는 {@code V4__create_document.sql}.
 */
@Getter
@Entity
@Table(name = "document")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document extends BaseTimeEntity {

    /** 사용자 업로드 문서의 종류 값. 화면에는 {@code internal}(사내 자료)로 보인다. */
    public static final String UPLOAD_TYPE = "INTERNAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String company;

    @Column(nullable = false, length = 30)
    private String documentType;

    @Column(nullable = false)
    private String title;

    private Document(String company, String documentType, String title) {
        this.company = company;
        this.documentType = documentType;
        this.title = title;
    }

    /** 사용자가 올린 문서를 만든다. 종류는 {@link #UPLOAD_TYPE}로 고정한다. */
    public static Document upload(String company, String title) {
        return new Document(company, UPLOAD_TYPE, title);
    }
}
