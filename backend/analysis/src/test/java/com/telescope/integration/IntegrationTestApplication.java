package com.telescope.integration;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 테스트 전용 통합 컨텍스트. auth·canvas를 각자 서비스로 나누기 전부터 있던 통합 테스트를 위해
 * analysis·common·auth·canvas를 한 프로세스로 띄운다. 운영 시작점은 {@code AnalysisApplication}(analysis·common만)이다.
 * 테스트를 각 모듈로 옮기면(후속) 이 클래스는 사라진다. analysis 자동 탐지와 겹치지 않게 별도 패키지에 둔다.
 */
@SpringBootApplication(scanBasePackages = {
        "com.telescope.analysis", "com.telescope.common", "com.telescope.integration",
        "com.telescope.auth.user", "com.telescope.canvas.canvas", "com.telescope.canvas.global"})
@AutoConfigurationPackage(basePackages = {
        "com.telescope.analysis", "com.telescope.auth.user", "com.telescope.canvas.canvas"})
public class IntegrationTestApplication {
}
