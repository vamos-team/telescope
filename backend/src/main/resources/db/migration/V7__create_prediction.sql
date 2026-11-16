CREATE TABLE prediction_run (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    analysis_run_id BIGINT      NOT NULL,
    as_of_date      DATE        NOT NULL,
    job_id          BIGINT      NOT NULL,
    requested_by    BIGINT      NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_prediction_run_job UNIQUE (job_id),
    CONSTRAINT fk_prediction_run_analysis FOREIGN KEY (analysis_run_id) REFERENCES analysis_run (id),
    CONSTRAINT fk_prediction_run_job FOREIGN KEY (job_id) REFERENCES job (id),
    CONSTRAINT fk_prediction_run_requester FOREIGN KEY (requested_by) REFERENCES users (id),
    INDEX idx_prediction_run_analysis (analysis_run_id),
    INDEX idx_prediction_run_requester (requested_by)
);

CREATE TABLE predicted_question (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    prediction_run_id BIGINT      NOT NULL,
    question          TEXT        NOT NULL,
    rationale         TEXT        NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_predicted_question_run FOREIGN KEY (prediction_run_id) REFERENCES prediction_run (id),
    INDEX idx_predicted_question_run (prediction_run_id)
);

CREATE TABLE prediction_evaluation (
    id                           BIGINT       NOT NULL AUTO_INCREMENT,
    predicted_question_id        BIGINT       NOT NULL,
    compared_document_version_id BIGINT       NOT NULL,
    as_of_date                   DATE         NOT NULL,
    evaluated_at                 DATETIME(6)  NOT NULL,
    result                       VARCHAR(30)  NOT NULL,
    created_at                   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_prediction_evaluation_question FOREIGN KEY (predicted_question_id) REFERENCES predicted_question (id),
    CONSTRAINT fk_prediction_evaluation_document FOREIGN KEY (compared_document_version_id) REFERENCES document_version (id),
    INDEX idx_prediction_evaluation_question (predicted_question_id),
    INDEX idx_prediction_evaluation_document (compared_document_version_id)
);
