CREATE TABLE document (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    company       VARCHAR(100) NOT NULL,
    document_type VARCHAR(30)  NOT NULL,
    title         VARCHAR(255) NOT NULL,
    created_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_document_company_type (company, document_type)
);

-- uploaded_by가 NULL이면 수집된 공용 문서
CREATE TABLE document_version (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    document_id       BIGINT       NOT NULL,
    job_id            BIGINT       NOT NULL,
    uploaded_by       BIGINT       NULL,
    version_no        INT          NOT NULL,
    published_date    DATE         NOT NULL,
    source            VARCHAR(255) NOT NULL,
    file_storage_key  VARCHAR(500) NOT NULL,
    created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_document_version_job UNIQUE (job_id),
    CONSTRAINT uk_document_version_no UNIQUE (document_id, version_no),
    CONSTRAINT fk_document_version_document FOREIGN KEY (document_id) REFERENCES document (id),
    CONSTRAINT fk_document_version_job FOREIGN KEY (job_id) REFERENCES job (id),
    CONSTRAINT fk_document_version_uploader FOREIGN KEY (uploaded_by) REFERENCES users (id),
    INDEX idx_document_version_published (published_date),
    INDEX idx_document_version_uploader (uploaded_by)
);
