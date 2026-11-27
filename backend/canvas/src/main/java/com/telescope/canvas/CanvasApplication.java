package com.telescope.canvas;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** canvas 코드를 work도 함께 쓰므로 work가 스캔하지 않는 패키지에 둔다. */
@SpringBootApplication(scanBasePackages = {"com.telescope.canvas", "com.telescope.backend.global", "com.telescope.backend.canvas"})
@EntityScan(basePackages = "com.telescope.backend.canvas")
@EnableJpaRepositories(basePackages = "com.telescope.backend.canvas")
public class CanvasApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(CanvasApplication.class).properties("spring.config.name=canvas").run(args);
    }
}
