-- 작업 결과 저장 실패 횟수 (#63). ai가 SUCCEEDED를 알려 줬는데 대상 도메인이 결과를 저장하지 못하면
-- 작업을 끝내지 않고 다음 조회에서 다시 시도한다. 이 횟수가 한도(app.job.result-save-max-attempts)에 닿으면 FAILED로 끝낸다.
ALTER TABLE job ADD COLUMN result_save_failures INT NOT NULL DEFAULT 0;
