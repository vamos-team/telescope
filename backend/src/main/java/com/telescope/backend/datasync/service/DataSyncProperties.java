package com.telescope.backend.datasync.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 재무 데이터 적재 설정({@code app.datasync}).
 *
 * @param corpCodes 대상 회사 DART 고유번호. 비어 있으면 적재를 시작하지 않는다
 * @param years     공시 접수 기간. 오늘부터 몇 년 전까지 받을지
 */
@ConfigurationProperties(prefix = "app.datasync")
public record DataSyncProperties(
        List<String> corpCodes,
        @DefaultValue("5") int years
) {

    /** 값이 없거나 비어 있으면 빈 목록으로 둔다. 앞뒤 공백은 뺀다. */
    public DataSyncProperties {
        corpCodes = corpCodes == null ? List.of() : corpCodes.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        if (years < 1) {
            throw new IllegalStateException("app.datasync.years는 1 이상이어야 합니다.");
        }
    }
}
