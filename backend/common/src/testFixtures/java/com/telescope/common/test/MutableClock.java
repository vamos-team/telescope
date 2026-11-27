package com.telescope.analysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 테스트에서 시각을 앞으로 돌려 볼 수 있는 Clock. 시스템 기본 시간대를 쓴다(운영 {@code ClockConfig}와 같음). */
public class MutableClock extends Clock {

    private final ZoneId zone = ZoneId.systemDefault();
    private volatile Instant instant;

    public MutableClock(LocalDateTime start) {
        set(start);
    }

    public void set(LocalDateTime time) {
        instant = time.atZone(zone).toInstant();
    }

    public void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(instant, zone);
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
    @Override public Instant instant() { return instant; }
}
