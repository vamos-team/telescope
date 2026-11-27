package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.UserRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 구독 기록·접속자 계산·커서 빈도 제한. 시각은 직접 넣어 결과가 시간에 흔들리지 않게 한다. */
class CanvasRealtimeRegistryTest {

    private static final long MS = 1_000_000L;

    CanvasRealtimeRegistry registry = new CanvasRealtimeRegistry();

    @Test
    void 커서는_같은_세션_캔버스에서_50ms_안에_다시_오면_버린다() {
        assertThat(registry.allowCursor("s1", 1L, 0)).isTrue();
        assertThat(registry.allowCursor("s1", 1L, 10 * MS)).isFalse();
        assertThat(registry.allowCursor("s1", 1L, 49 * MS)).isFalse();
        assertThat(registry.allowCursor("s1", 1L, 50 * MS)).isTrue();
    }

    @Test
    void 커서_빈도_제한은_세션과_캔버스마다_따로_센다() {
        assertThat(registry.allowCursor("s1", 1L, 0)).isTrue();
        assertThat(registry.allowCursor("s2", 1L, 1 * MS)).isTrue();
        assertThat(registry.allowCursor("s1", 2L, 1 * MS)).isTrue();
    }

    @Test
    void 한_사람이_창을_여러_개_열어도_마지막_창이_닫힐_때만_나간다() {
        assertThat(registry.add(sub("s1", "a", CanvasDestination.PRESENCE))).isTrue();
        assertThat(registry.add(sub("s2", "b", CanvasDestination.PRESENCE))).isFalse();

        assertThat(registry.removeSession("s1")).isEmpty();
        assertThat(registry.presentUsers(1L)).extracting(UserRef::id).containsExactly(7L);
        assertThat(registry.removeSession("s2")).hasSize(1);
        assertThat(registry.presentUsers(1L)).isEmpty();
    }

    @Test
    void 권한이_풀리면_그_사람의_구독_기록을_모두_지운다() {
        registry.add(sub("s1", "a", CanvasDestination.EVENTS));
        registry.add(sub("s1", "b", CanvasDestination.PRESENCE));

        registry.revoke(1L, List.of(7L));

        assertThat(registry.isSubscribed("s1", 1L)).isFalse();
        assertThat(registry.presentUsers(1L)).isEmpty();
    }

    private static CanvasRealtimeRegistry.Subscription sub(String session, String id, String channel) {
        return new CanvasRealtimeRegistry.Subscription(session, id, 1L, new UserRef(7L, "이재무", "네트워크센터"), channel);
    }
}
