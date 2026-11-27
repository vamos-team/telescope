-- #193 MSA 분리: users·캔버스 테이블을 서비스별 스키마로 옮기고, 서비스를 넘는 FK를 해제한다.
-- 되돌리기는 docs/backend/msa-split-runbook.md 의 역방향 SQL. app 스키마의 나머지 테이블은 그대로 둔다.
-- IDENTITY 컬럼의 시퀀스와 서비스 내부 FK는 SET SCHEMA 때 테이블과 함께 옮겨진다.

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS canvas;

-- 1) 서비스를 넘는 외래키 16개 해제. DB 결합을 없애고, 참조 무결성은 각 서비스가 맡는다.
ALTER TABLE analysis_run     DROP CONSTRAINT fk_analysis_run_requester;
ALTER TABLE chat_session     DROP CONSTRAINT fk_chat_session_user;
ALTER TABLE data_sync_run    DROP CONSTRAINT fk_data_sync_run_requested_by;
ALTER TABLE document_version DROP CONSTRAINT fk_document_version_uploader;
ALTER TABLE document_version DROP CONSTRAINT fk_document_version_canvas;
ALTER TABLE prediction_run   DROP CONSTRAINT fk_prediction_run_requester;
ALTER TABLE canvas           DROP CONSTRAINT fk_canvas_owner;
ALTER TABLE canvas           DROP CONSTRAINT fk_canvas_last_edited_by;
ALTER TABLE canvas_share     DROP CONSTRAINT fk_canvas_share_user;
ALTER TABLE canvas_card      DROP CONSTRAINT fk_canvas_card_creator;
ALTER TABLE canvas_card      DROP CONSTRAINT fk_canvas_card_memo_by;
ALTER TABLE canvas_link      DROP CONSTRAINT fk_canvas_link_creator;
ALTER TABLE canvas_link      DROP CONSTRAINT fk_canvas_link_memo_by;
ALTER TABLE canvas_mapping   DROP CONSTRAINT fk_canvas_mapping_user;
ALTER TABLE canvas_event     DROP CONSTRAINT fk_canvas_event_author;
ALTER TABLE canvas_request   DROP CONSTRAINT fk_canvas_request_author;

-- 2) auth 서비스 테이블을 auth 스키마로
ALTER TABLE users         SET SCHEMA auth;
ALTER TABLE login_attempt SET SCHEMA auth;

-- 3) canvas 서비스 테이블을 canvas 스키마로
ALTER TABLE canvas         SET SCHEMA canvas;
ALTER TABLE canvas_share   SET SCHEMA canvas;
ALTER TABLE canvas_card    SET SCHEMA canvas;
ALTER TABLE canvas_link    SET SCHEMA canvas;
ALTER TABLE canvas_mapping SET SCHEMA canvas;
ALTER TABLE canvas_event   SET SCHEMA canvas;
ALTER TABLE canvas_request SET SCHEMA canvas;
