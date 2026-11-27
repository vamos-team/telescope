package com.telescope.backend.datasync.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 적재 배치의 시작 신호와 결과 확인을 정해진 때에 낸다. 일은 {@link DataSyncService}가 한다.
 *
 * <ul>
 *   <li>시작: {@code app.datasync.cron}(서울 시간). {@code -}이면 꺼진다(로컬·테스트 기본값). 배포는 {@code DATASYNC_CRON}으로 켠다</li>
 *   <li>결과 확인: {@code app.datasync.poll-interval}마다. 끝나지 않은 적재가 없으면 DB 조회 한 번으로 끝난다</li>
 * </ul>
 * 예외는 여기서 로그로만 남긴다. 스케줄러 스레드로 올리면 그 회차가 조용히 사라진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSyncScheduler {

    private final DataSyncService dataSyncService;

    /** 정해진 시각에 적재를 시작한다. 이미 진행 중이면 서비스가 새로 만들지 않는다. */
    @Scheduled(cron = "${app.datasync.cron:-}", zone = "Asia/Seoul")
    public void startScheduled() {
        try {
            DataSyncService.StartResult result = dataSyncService.start(null);
            log.info("예약 적재: runId={}, started={}", result.run().getId(), result.started());
        } catch (RuntimeException e) {
            log.error("예약 적재를 시작하지 못했다", e);
        }
    }

    /** 끝나지 않은 적재 작업의 결과를 확인한다. */
    @Scheduled(fixedDelayString = "${app.datasync.poll-interval:PT1M}", initialDelayString = "${app.datasync.poll-interval:PT1M}")
    public void pollScheduled() {
        try {
            dataSyncService.pollUnfinished();
        } catch (RuntimeException e) {
            log.error("적재 결과 확인 실패", e);
        }
    }
}
