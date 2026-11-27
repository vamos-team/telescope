package com.telescope.canvas.canvas.repository;

import com.telescope.integration.IntegrationTestApplication;
import org.springframework.test.context.ContextConfiguration;
import com.telescope.analysis.TestcontainersConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V12(프롬프트 → 메모, 관계 종류 삭제)가 예전 모양의 행을 새 규약으로 바꾸는지.
 *
 * <p>앱이 쓰는 {@code app} 스키마와 따로 임시 스키마를 만들어 V11까지 올리고, 예전 모양의 카드·연결·이벤트를 넣은 뒤
 * V12를 적용해 결과를 본다. 끝나면 임시 스키마를 지운다.
 */
@DataJpaTest
@ContextConfiguration(classes = IntegrationTestApplication.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CanvasMemoMigrationTest {

    private static final String SCHEMA = "v12_check";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void 예전_프롬프트_데이터를_메모_규약으로_바꾼다() {
        migrate("11");
        Long user = jdbc.queryForObject("insert into " + SCHEMA + ".users (login_id, password_hash, name, role)"
                + " values ('m', 'x', 'm', 'USER') returning id", Long.class);
        Long canvas = jdbc.queryForObject("insert into " + SCHEMA + ".canvas (name, quarter, owner_id)"
                + " values ('c', '2026Q1', ?) returning id", Long.class, user);
        UUID memo = UUID.randomUUID(), table = UUID.randomUUID(), compare = UUID.randomUUID(), chart = UUID.randomUUID();
        insertCard(memo, canvas, "PROMPT", "{}", user, "본문", null);
        insertCard(table, canvas, "TABLE", "{\"tableId\":\"t\"}", user, null, "카드 프롬프트");
        insertCard(compare, canvas, "COMPARE", "{\"sources\":[\"" + table + "\"]}", user, null, null);
        insertCard(chart, canvas, "CHART", "{\"compareId\":\"" + compare + "\"}", user, null, null);
        UUID link = UUID.randomUUID();
        jdbc.update("insert into " + SCHEMA + ".canvas_link (id, canvas_id, source_card_id, target_card_id, rel, prompt, created_by)"
                + " values (?, ?, ?, ?, '근거', '선 프롬프트', ?)", link, canvas, table, memo, user);
        jdbc.update("insert into " + SCHEMA + ".canvas_event (canvas_id, seq, request_id, author_id, type, payload)"
                + " values (?, 1, ?, ?, 'add_prompt', '{\"type\":\"add_prompt\",\"cardId\":\"x\"}'::jsonb)", canvas, UUID.randomUUID(), user);
        jdbc.update("insert into " + SCHEMA + ".canvas_event (canvas_id, seq, request_id, author_id, type, payload)"
                + " values (?, 2, ?, ?, 'move_card', '{\"type\":\"move_card\"}'::jsonb)", canvas, UUID.randomUUID(), user);

        migrate("12");

        assertThat(jdbc.queryForObject("select kind from " + SCHEMA + ".canvas_card where id = ?", String.class, memo))
                .isEqualTo("MEMO");
        assertThat(jdbc.queryForObject("select memo from " + SCHEMA + ".canvas_card where id = ?", String.class, table))
                .isEqualTo("카드 프롬프트");
        assertThat(jdbc.queryForObject("select props ->> 'sources' from " + SCHEMA + ".canvas_card where id = ?",
                String.class, chart)).isEqualTo("[\"" + table + "\"]");
        Map<String, Object> l = jdbc.queryForMap("select * from " + SCHEMA + ".canvas_link where id = ?", link);
        assertThat(l).containsEntry("memo", "선 프롬프트").containsKey("memo_by").doesNotContainKey("rel");
        List<Map<String, Object>> events = jdbc.queryForList(
                "select type, payload ->> 'type' as payload_type from " + SCHEMA + ".canvas_event order by seq");
        assertThat(events.get(0)).containsEntry("type", "add_memo").containsEntry("payload_type", "add_memo");
        assertThat(events.get(1)).containsEntry("type", "move_card");
    }

    private void insertCard(UUID id, Long canvas, String kind, String props, Long user, String text, String prompt) {
        jdbc.update("insert into " + SCHEMA + ".canvas_card (id, canvas_id, kind, props, text, prompt, created_by)"
                + " values (?, ?, ?, ?::jsonb, ?, ?, ?)", id, canvas, kind, props, text, prompt, user);
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).schemas(SCHEMA).target(target).load().migrate();
    }
}
