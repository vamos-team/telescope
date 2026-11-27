package com.telescope.analysis.datasync.service;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 스케줄러는 서비스를 부르기만 하고, 예외가 스케줄러 스레드로 올라가지 않게 막는다. 설정 이름도 확인한다. */
class DataSyncSchedulerTest {

    DataSyncService service = mock(DataSyncService.class);
    DataSyncScheduler scheduler = new DataSyncScheduler(service);

    @Test
    void 예약_시작은_요청자_없이_시작하고_실패해도_예외를_올리지_않는다() {
        when(service.start(null)).thenThrow(new IllegalStateException("ai 꺼짐"));

        assertThatCode(scheduler::startScheduled).doesNotThrowAnyException();
        verify(service).start(null);
    }

    @Test
    void 결과_확인은_끝나지_않은_작업을_확인하고_실패해도_예외를_올리지_않는다() {
        when(service.pollUnfinished()).thenThrow(new IllegalStateException("DB 끊김"));

        assertThatCode(scheduler::pollScheduled).doesNotThrowAnyException();
        verify(service).pollUnfinished();
    }

    @Test
    void 시작은_cron_설정을_서울_시간으로_쓰고_기본은_꺼져_있다() throws Exception {
        Scheduled start = DataSyncScheduler.class.getMethod("startScheduled").getAnnotation(Scheduled.class);
        assertThat(start.cron()).isEqualTo("${app.datasync.cron:-}");
        assertThat(start.zone()).isEqualTo("Asia/Seoul");
    }
}
