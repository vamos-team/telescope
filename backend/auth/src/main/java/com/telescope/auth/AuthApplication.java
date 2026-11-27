package com.telescope.auth;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** user 코드를 work도 함께 쓰므로 work가 스캔하지 않는 패키지에 둔다. */
@SpringBootApplication(scanBasePackages = {"com.telescope.auth", "com.telescope.backend.global", "com.telescope.backend.user"})
@EntityScan(basePackages = "com.telescope.backend.user")
@EnableJpaRepositories(basePackages = "com.telescope.backend.user")
public class AuthApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AuthApplication.class).properties("spring.config.name=auth").run(args);
    }
}
