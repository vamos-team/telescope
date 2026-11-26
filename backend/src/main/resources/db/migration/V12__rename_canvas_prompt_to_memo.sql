-- 캔버스 프롬프트를 메모로 바꾸고, 연결 관계 종류를 없앤다 (#123). 화면 규약은 PR #122, 결정 이유는 docs/backend/canvas.md 7장
-- 로그인(#34) 전이라 실제 캔버스 데이터는 없지만, 테스트·개발 DB에 남은 행도 새 규약으로 맞춘다.

-- 카드: 프롬프트 카드 → 메모 카드, 카드 프롬프트 → 카드 메모
ALTER TABLE canvas_card RENAME COLUMN prompt TO memo;
ALTER TABLE canvas_card RENAME COLUMN prompt_version TO memo_version;
UPDATE canvas_card SET kind = 'MEMO' WHERE kind = 'PROMPT';

-- 시각화 카드는 비교 카드 대신 원본 표 목록(sources)을 갖는다. 비교 카드에서 만든 기존 카드는 그 비교의 원본을 옮겨 온다.
UPDATE canvas_card chart
SET props = chart.props || jsonb_build_object('sources', COALESCE(compare.props -> 'sources', '[]'::jsonb))
FROM canvas_card compare
WHERE chart.kind = 'CHART'
  AND compare.kind = 'COMPARE'
  AND compare.id::text = chart.props ->> 'compareId';
UPDATE canvas_card SET props = props || '{"sources": []}'::jsonb
WHERE kind = 'CHART' AND NOT props ? 'sources';

-- 연결: 선 프롬프트 → 선 메모, 마지막으로 고친 사람(memo_by), 관계 종류(rel) 삭제
ALTER TABLE canvas_link RENAME COLUMN prompt TO memo;
ALTER TABLE canvas_link RENAME COLUMN prompt_version TO memo_version;
ALTER TABLE canvas_link ADD COLUMN memo_by BIGINT NULL;
ALTER TABLE canvas_link
    ADD CONSTRAINT fk_canvas_link_memo_by FOREIGN KEY (memo_by) REFERENCES users (id);
ALTER TABLE canvas_link DROP COLUMN rel;

-- 이벤트 기록의 옛 이름을 새 이름으로 바꾼다(이 이름 변경에 한해 "이벤트 기록은 고치지 않는다"의 예외. canvas.md D25)
UPDATE canvas_event
SET type    = CASE type
                  WHEN 'add_prompt' THEN 'add_memo'
                  WHEN 'edit_prompt' THEN 'edit_memo'
                  WHEN 'set_card_prompt' THEN 'set_card_memo'
                  WHEN 'set_link_prompt' THEN 'set_link_memo'
              END,
    payload = jsonb_set(payload, '{type}', to_jsonb(CASE type
                  WHEN 'add_prompt' THEN 'add_memo'
                  WHEN 'edit_prompt' THEN 'edit_memo'
                  WHEN 'set_card_prompt' THEN 'set_card_memo'
                  WHEN 'set_link_prompt' THEN 'set_link_memo'
              END))
WHERE type IN ('add_prompt', 'edit_prompt', 'set_card_prompt', 'set_link_prompt');
