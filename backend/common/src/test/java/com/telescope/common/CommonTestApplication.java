package com.telescope.common;

import com.telescope.common.config.JpaAuditingConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.bind.annotation.RestController;

/**
 * common 모듈 테스트용 컨텍스트. 보안·필터·JWT만 보므로 DB·JPA 감사 설정은 뺀다.
 * 테스트 컨트롤러는 스캔하지 않고 각 테스트가 @Import로 자기 것만 올린다(경로 충돌 방지).
 */
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JpaAuditingConfig.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestController.class)
})
public class CommonTestApplication {
}
