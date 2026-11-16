CREATE TABLE analysis_run (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    as_of_date      DATE        NOT NULL,
    data_version_id BIGINT      NOT NULL,
    job_id          BIGINT      NOT NULL,
    requested_by    BIGINT      NOT NULL,
    user_input      TEXT        NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_run_job UNIQUE (job_id),
    CONSTRAINT fk_analysis_run_data_version FOREIGN KEY (data_version_id) REFERENCES data_version (id),
    CONSTRAINT fk_analysis_run_job FOREIGN KEY (job_id) REFERENCES job (id),
    CONSTRAINT fk_analysis_run_requester FOREIGN KEY (requested_by) REFERENCES users (id),
    INDEX idx_analysis_run_requester (requested_by),
    INDEX idx_analysis_run_data_version (data_version_id)
);

CREATE TABLE analysis_run_document (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    analysis_run_id     BIGINT      NOT NULL,
    document_version_id BIGINT      NOT NULL,
    role                VARCHAR(20) NOT NULL,
    created_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_analysis_run_document UNIQUE (analysis_run_id, document_version_id),
    CONSTRAINT fk_analysis_run_document_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id),
    CONSTRAINT fk_analysis_run_document_version FOREIGN KEY (document_version_id) REFERENCES document_version (id),
    INDEX idx_analysis_run_document_version (document_version_id)
);

CREATE TABLE analysis_finding (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    analysis_run_id  BIGINT       NOT NULL,
    indicator        VARCHAR(255) NOT NULL,
    disclosed_value  VARCHAR(255) NULL,
    evidence_ref     VARCHAR(500) NULL,
    is_matched       BOOLEAN      NOT NULL,
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_analysis_finding_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id),
    INDEX idx_analysis_finding_run (analysis_run_id)
);
