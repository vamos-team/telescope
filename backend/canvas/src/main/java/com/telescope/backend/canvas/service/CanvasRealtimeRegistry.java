package com.telescope.backend.canvas.service;

import com.telescope.backend.canvas.dto.UserRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 지금 연결된 세션이 어느 캔버스를 구독하고 있는지 기억한다(서버 메모리, 파드 1개 전제).
 *
 * <ul>
 *   <li>권한 확인을 통과한 구독만 기록한다. 보내는 쪽 필터는 기록에 없는 세션에 캔버스 메시지를 보내지 않는다.
 *       그래서 공유가 풀린 사람은 기록에서 빼기만 하면 더 받지 못한다(브로커 구독을 서버가 직접 끊을 수 없어서).</li>
 *   <li>접속자: 한 사람이 창을 여러 개 열어도 한 번만 센다. 그 사람의 마지막 창이 닫힐 때 나감으로 본다.</li>
 *   <li>커서 보내기 빈도를 세션·캔버스마다 제한한다(초당 20번 = 50ms 간격).</li>
 * </ul>
 */
@Component
public class CanvasRealtimeRegistry {

    /** 커서 메시지 최소 간격(나노초). 초당 20번. */
    static final long CURSOR_INTERVAL_NANOS = 50_000_000L;

    /** 세션 ID → (구독 ID → 구독). */
    private final Map<String, Map<String, Subscription>> sessions = new ConcurrentHashMap<>();
    /** (세션 ID + 캔버스 ID) → 마지막으로 커서를 통과시킨 시각(나노초). */
    private final Map<String, Long> lastCursor = new ConcurrentHashMap<>();

    /**
     * 권한 확인을 통과한 구독 하나.
     *
     * @param user    구독한 사람. 이름·부서는 구독할 때 한 번 읽어 둔다. 커서(초당 최대 20번)·접속자 알림이 매번 DB를 보지 않게(#149)
     * @param channel {@link CanvasDestination#EVENTS} / {@link CanvasDestination#CURSORS} / {@link CanvasDestination#PRESENCE}
     */
    public record Subscription(String sessionId, String subscriptionId, Long canvasId, UserRef user, String channel) {

        /** 구독한 사람의 ID. */
        public Long userId() {
            return user.id();
        }
    }

    /**
     * 구독을 기록한다.
     *
     * @return 접속자 구독이고 이 사람이 이 캔버스에 처음 들어온 것이면 true(들어옴 알림을 보낼 때)
     */
    public synchronized boolean add(Subscription sub) {
        boolean wasPresent = isPresent(sub.canvasId(), sub.userId());
        sessions.computeIfAbsent(sub.sessionId(), k -> new ConcurrentHashMap<>()).put(sub.subscriptionId(), sub);
        return CanvasDestination.PRESENCE.equals(sub.channel()) && !wasPresent;
    }

    /**
     * 구독 하나를 뺀다(화면이 구독을 풀 때).
     *
     * @return 이 때문에 캔버스에서 완전히 나간 (캔버스, 사람). 없으면 빈 목록
     */
    public synchronized List<Subscription> remove(String sessionId, String subscriptionId) {
        Map<String, Subscription> subs = sessions.get(sessionId);
        if (subs == null) {
            return List.of();
        }
        Subscription removed = subs.remove(subscriptionId);
        return removed == null ? List.of() : leftAfterRemoving(List.of(removed));
    }

    /**
     * 세션의 구독을 모두 뺀다(연결이 끊길 때).
     *
     * @return 이 때문에 캔버스에서 완전히 나간 (캔버스, 사람)
     */
    public synchronized List<Subscription> removeSession(String sessionId) {
        Map<String, Subscription> subs = sessions.remove(sessionId);
        lastCursor.keySet().removeIf(k -> k.startsWith(sessionId + ":"));
        return subs == null ? List.of() : leftAfterRemoving(subs.values());
    }

    /**
     * 이 캔버스에서 주어진 사람들의 구독을 모두 뺀다(공유가 풀리거나 캔버스가 삭제될 때).
     *
     * @return 이 때문에 캔버스에서 완전히 나간 (캔버스, 사람)
     */
    public synchronized List<Subscription> revoke(Long canvasId, Collection<Long> userIds) {
        List<Subscription> removed = new ArrayList<>();
        for (Map<String, Subscription> subs : sessions.values()) {
            subs.values().removeIf(s -> {
                boolean hit = s.canvasId().equals(canvasId) && userIds.contains(s.userId());
                if (hit) {
                    removed.add(s);
                }
                return hit;
            });
        }
        return leftAfterRemoving(removed);
    }

    /** 이 세션이 이 캔버스를 (권한 확인을 거쳐) 구독하고 있는지. 보내는 쪽 필터와 커서 받기에서 쓴다. */
    public boolean isSubscribed(String sessionId, Long canvasId) {
        Map<String, Subscription> subs = sessions.get(sessionId);
        return subs != null && subs.values().stream().anyMatch(s -> s.canvasId().equals(canvasId));
    }

    /** 이 캔버스에 지금 들어와 있는 사람(접속자 구독 기준). 한 사람이 창을 여러 개 열어도 한 번만. */
    public synchronized List<UserRef> presentUsers(Long canvasId) {
        Map<Long, UserRef> users = new LinkedHashMap<>();
        for (Map<String, Subscription> subs : sessions.values()) {
            for (Subscription s : subs.values()) {
                if (s.canvasId().equals(canvasId) && CanvasDestination.PRESENCE.equals(s.channel())) {
                    users.putIfAbsent(s.userId(), s.user());
                }
            }
        }
        return List.copyOf(users.values());
    }

    /** 이 세션이 이 캔버스를 구독할 때 기록한 사람 정보. 커서를 보낼 때 이름을 붙이려고 쓴다. 구독이 없으면 비어 있다. */
    public Optional<UserRef> personOf(String sessionId, Long canvasId) {
        Map<String, Subscription> subs = sessions.get(sessionId);
        if (subs == null) {
            return Optional.empty();
        }
        return subs.values().stream().filter(s -> s.canvasId().equals(canvasId)).map(Subscription::user).findFirst();
    }

    /**
     * 커서 메시지를 통과시킬지 정한다. 같은 세션·캔버스에서 50ms 안에 다시 오면 버린다.
     *
     * @param nowNanos 지금 시각({@code System.nanoTime()}). 테스트에서 시각을 정해 넣을 수 있게 받는다
     */
    public boolean allowCursor(String sessionId, Long canvasId, long nowNanos) {
        String key = sessionId + ":" + canvasId;
        boolean[] allowed = {false};
        lastCursor.compute(key, (k, last) -> {
            if (last == null || nowNanos - last >= CURSOR_INTERVAL_NANOS) {
                allowed[0] = true;
                return nowNanos;
            }
            return last;
        });
        return allowed[0];
    }

    private boolean isPresent(Long canvasId, Long userId) {
        return presentUsers(canvasId).stream().anyMatch(u -> u.id().equals(userId));
    }

    /** 뺀 구독 중 접속자 구독에 대해, 그 사람이 그 캔버스에 더는 없으면 나감으로 돌려준다. */
    private List<Subscription> leftAfterRemoving(Collection<Subscription> removed) {
        List<Subscription> left = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Subscription s : removed) {
            if (CanvasDestination.PRESENCE.equals(s.channel())
                    && seen.add(s.canvasId() + ":" + s.userId())
                    && !isPresent(s.canvasId(), s.userId())) {
                left.add(s);
            }
        }
        return left;
    }
}
