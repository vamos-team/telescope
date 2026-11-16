CREATE TABLE job (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    job_type        VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    external_job_id VARCHAR(100) NULL,
    error_message   TEXT         NULL,
    started_at      DATETIME(6)  NULL,
    finished_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id)
);
