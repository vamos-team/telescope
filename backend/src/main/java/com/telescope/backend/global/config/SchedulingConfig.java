package com.telescope.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * {@code @Scheduled} 배치(재무 데이터 적재 #135)를 켠다.
 *
 * <p>WebSocket 하트비트가 쓰는 {@code messageBrokerTaskScheduler}와 섞이지 않게 배치 전용 스케줄러를 따로 둔다.
 * 오래 걸리는 확인 작업이 하트비트를 늦추지 않게 하려는 것이다. 빈으로 등록해 종료 때 스레드가 정리된다.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    /** 배치 전용 스케줄러. 시작 신호와 결과 확인이 겹쳐도 서로 기다리지 않게 스레드 두 개. */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskScheduler batchTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("batch-");
        scheduler.initialize();
        return scheduler;
    }

    /** {@code @Scheduled} 작업을 배치 전용 스케줄러에서 돌린다. */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(batchTaskScheduler());
    }
}
