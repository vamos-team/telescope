-- 로그인 실패 횟수와 잠금(#164). 규약은 docs/backend/auth.md
-- 사번별로 연속 실패 횟수를 센다. 없는 사번도 같은 방식으로 세어야 응답으로 사번 존재 여부를 알 수 없으므로 users와 FK를 두지 않는다.
-- 서버를 다시 켜도 잠금이 유지되도록 DB에 둔다. 로그인에 성공하면 횟수를 0으로 되돌린다(행은 남긴다).
CREATE TABLE login_attempt (
    employee_no  VARCHAR(100) NOT NULL,
    failed_count INT          NOT NULL DEFAULT 0,
    locked_until TIMESTAMP(6) NULL,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (employee_no),
    CONSTRAINT ck_login_attempt_failed_count CHECK (failed_count >= 0)
);
