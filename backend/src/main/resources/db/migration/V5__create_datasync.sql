CREATE TABLE data_sync_run (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    source      VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    started_at  DATETIME(6)  NULL,
    finished_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_data_sync_run_status (status)
);

CREATE TABLE data_version (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    data_sync_run_id BIGINT      NOT NULL,
    is_active        BOOLEAN     NOT NULL,
    created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_data_version_run FOREIGN KEY (data_sync_run_id) REFERENCES data_sync_run (id),
    INDEX idx_data_version_active (is_active),
    INDEX idx_data_version_run (data_sync_run_id)
);
