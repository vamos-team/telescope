package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.CanvasEventsRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 캔버스 서비스 테스트에서 쓰는 사용자·이벤트 만들기 도우미. */
final class CanvasTestSupport {

    private CanvasTestSupport() {
    }

    /** 테스트 사용자를 만든다. 테스트끼리 커밋된 데이터가 섞이지 않도록 사번을 매번 다르게 한다. */
    static Long user(JdbcTemplate jdbc) {
        String employeeNo = "t-" + UUID.randomUUID();
        return jdbc.queryForObject(
                "insert into app.users (employee_no, password_hash, name, role) values (?, 'x', ?, 'USER') returning id",
                Long.class, employeeNo, employeeNo);
    }

    /** 새 requestId로 이벤트 묶음을 만든다. */
    @SafeVarargs
    static CanvasEventsRequest batch(Map<String, Object>... events) {
        return new CanvasEventsRequest(UUID.randomUUID(), Arrays.asList(events));
    }

    /** {@code event("move_card", "cardId", id, "x", 1, "y", 2)}처럼 이벤트 한 건을 만든다. */
    static Map<String, Object> event(String type, Object... keyValues) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            Object v = keyValues[i + 1];
            e.put((String) keyValues[i], v instanceof UUID u ? u.toString() : v);
        }
        return e;
    }

    /** UUID 목록을 이벤트에 넣을 문자열 목록으로 바꾼다. */
    static List<String> ids(UUID... ids) {
        return Arrays.stream(ids).map(UUID::toString).toList();
    }
}
