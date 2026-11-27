-- 로그인 식별자를 사번으로 바꾸고 표시용 부서·직무를 더한다 (#34). 규약은 docs/backend/api-spec.md 3.1
-- login_id를 이름만 바꿔 사번으로 쓴다. 남아 있는 행의 값과 고유 제약은 그대로 이어진다.
ALTER TABLE users RENAME COLUMN login_id TO employee_no;
ALTER TABLE users RENAME CONSTRAINT uk_users_login_id TO uk_users_employee_no;

-- 부서·직무는 표시용이라 권한에 쓰지 않는다. 기존 행과 관리자 seed에 값이 없을 수 있어 NULL을 허용한다.
ALTER TABLE users ADD COLUMN dept VARCHAR(100) NULL;
ALTER TABLE users ADD COLUMN job  VARCHAR(100) NULL;
