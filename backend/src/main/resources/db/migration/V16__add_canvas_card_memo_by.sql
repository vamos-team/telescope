-- 카드 메모를 단 사람(#170). 메모는 단 사람만 고치고 지운다. 이 컬럼 전에 쓴 카드 메모는 작성자를 알 수 없어 비워 둔다.
ALTER TABLE canvas_card ADD COLUMN memo_by BIGINT NULL;
ALTER TABLE canvas_card
    ADD CONSTRAINT fk_canvas_card_memo_by FOREIGN KEY (memo_by) REFERENCES users (id);
