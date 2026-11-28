package com.telescope.common.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB가 필요한 테스트용 PostgreSQL 컨테이너
 * 배포 환경(RDS PostgreSQL 18)과 같은 메이저 버전을 쓰고, @ServiceConnection이 임의 포트와 계정으로 데이터소스를 연결
 * Flyway 마이그레이션도 이 컨테이너에서 실행
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18"));
	}
}
