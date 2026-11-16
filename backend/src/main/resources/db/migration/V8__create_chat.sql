CREATE TABLE chat_session (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    user_id    BIGINT       NOT NULL,
    title      VARCHAR(255) NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_session_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_chat_session_user (user_id)
);

CREATE TABLE chat_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    chat_session_id BIGINT      NOT NULL,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    created_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_chat_message_session FOREIGN KEY (chat_session_id) REFERENCES chat_session (id),
    INDEX idx_chat_message_session (chat_session_id)
);
