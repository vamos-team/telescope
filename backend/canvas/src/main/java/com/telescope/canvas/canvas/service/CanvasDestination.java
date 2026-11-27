package com.telescope.canvas.canvas.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 캔버스 실시간 주소(STOMP 목적지)를 해석한다. 규약은 {@code docs/backend/api-spec.md} 5.5.
 *
 * <ul>
 *   <li>{@code /topic/canvases/{id}/events|cursors|presence}: 구독(서버 → 화면)</li>
 *   <li>{@code /app/canvases/{id}/cursor}: 내 커서 보내기(화면 → 서버)</li>
 *   <li>{@code /app/canvases/{id}/presence}: 처음 한 번 지금 접속자 목록 받기</li>
 *   <li>{@code /user/queue/canvases}: 나에게만 오는 알림</li>
 * </ul>
 *
 * @param canvasId 캔버스 ID
 * @param channel  {@code events} / {@code cursors} / {@code presence} / {@code cursor} / {@code presence-snapshot}
 */
public record CanvasDestination(Long canvasId, String channel) {

    public static final String EVENTS = "events";
    public static final String CURSORS = "cursors";
    public static final String PRESENCE = "presence";
    public static final String CURSOR_SEND = "cursor";
    public static final String PRESENCE_SNAPSHOT = "presence-snapshot";
    public static final String USER_QUEUE = "/user/queue/canvases";

    private static final Pattern TOPIC = Pattern.compile("^/topic/canvases/(\\d+)/(events|cursors|presence)$");
    private static final Pattern APP = Pattern.compile("^/app/canvases/(\\d+)/(cursor|presence)$");

    /** 구독 주소({@code /topic/...})를 해석한다. 캔버스 주소가 아니면 비어 있다. */
    public static Optional<CanvasDestination> topic(String destination) {
        return match(TOPIC, destination).map(m -> new CanvasDestination(Long.valueOf(m.group(1)), m.group(2)));
    }

    /** 앱 주소({@code /app/...})를 해석한다. 접속자 목록 주소는 {@link #PRESENCE_SNAPSHOT}으로 구분한다. */
    public static Optional<CanvasDestination> app(String destination) {
        return match(APP, destination).map(m -> new CanvasDestination(Long.valueOf(m.group(1)),
                PRESENCE.equals(m.group(2)) ? PRESENCE_SNAPSHOT : CURSOR_SEND));
    }

    /** 캔버스의 구독 주소를 만든다. */
    public static String topicOf(Long canvasId, String channel) {
        return "/topic/canvases/" + canvasId + "/" + channel;
    }

    private static Optional<Matcher> match(Pattern pattern, String destination) {
        if (destination == null) {
            return Optional.empty();
        }
        Matcher m = pattern.matcher(destination);
        return m.matches() ? Optional.of(m) : Optional.empty();
    }
}
