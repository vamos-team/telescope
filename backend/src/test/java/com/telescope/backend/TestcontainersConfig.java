package com.telescope.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * DB가 필요한 테스트용 MySQL 컨테이너
 * 운영/로컬과 같은 MySQL 8.4를 쓰고, @ServiceConnection이 임의 포트와 계정으로 데이터소스를 연결
 * Flyway 마이그레이션도 이 컨테이너에서 실행
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

	@Bean
	@ServiceConnection
	MySQLContainer mysql() {
		return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
	}
}
