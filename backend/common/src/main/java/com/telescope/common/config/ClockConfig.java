package com.telescope.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 시각을 직접 {@code now()}로 찍지 않고 주입받아 쓰기 위한 Clock.
 * 테스트에서 고정 시각으로 바꿔 검증할 수 있다.
 *
 * <p>MVP: 시스템 기본 시간대를 쓴다. 시간대 정책(api-spec 미정 항목)이 정해지면 여기서 바꾼다.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
