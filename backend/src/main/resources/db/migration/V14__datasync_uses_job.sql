-- 재무 데이터 적재 실행이 공통 작업(job)을 쓰도록 바꾸고, 판마다 DuckDB 파일 위치를 남긴다 (#135).
-- 설계와 결정 이유는 docs/backend/datasync.md. 적재 코드가 없어 쌓인 행이 없으므로 NOT NULL 칼럼을 바로 더한다.

-- 실행 상태·시각은 job(QUEUED → RUNNING → SUCCEEDED/FAILED)에서 읽는다. 자료 준비(document_version.job_id)와 같은 모양
ALTER TABLE data_sync_run DROP COLUMN status;
ALTER TABLE data_sync_run DROP COLUMN started_at;
ALTER TABLE data_sync_run DROP COLUMN finished_at;
ALTER TABLE data_sync_run ADD COLUMN job_id BIGINT NOT NULL;
-- 관리자가 직접 실행했으면 그 사용자, 스케줄러가 시작했으면 비어 있다
ALTER TABLE data_sync_run ADD COLUMN requested_by BIGINT NULL;
ALTER TABLE data_sync_run ADD CONSTRAINT uk_data_sync_run_job UNIQUE (job_id);
ALTER TABLE data_sync_run ADD CONSTRAINT fk_data_sync_run_job FOREIGN KEY (job_id) REFERENCES job (id);
ALTER TABLE data_sync_run ADD CONSTRAINT fk_data_sync_run_requested_by FOREIGN KEY (requested_by) REFERENCES users (id);

-- 판마다 다른 DuckDB 파일(finance-<실행번호>.duckdb). 지난 판 파일도 남겨 분석이 기록한 판을 다시 볼 수 있게 한다
ALTER TABLE data_version ADD COLUMN file_path VARCHAR(500) NOT NULL;
-- 활성 판은 언제나 하나뿐이다
CREATE UNIQUE INDEX uk_data_version_active ON data_version (is_active) WHERE is_active;
