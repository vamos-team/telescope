package com.telescope.canvas;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * canvas 서비스(캔버스·공유 권한·실시간). canvas 코드는 work도 함께 쓰므로, 이 시작 클래스는 work가 스캔하지 않는 패키지에 둔다.
 * 설정 파일은 work의 application.yml과 섞이지 않게 canvas.yml을 읽는다.
 */
@SpringBootApplication(scanBasePackages = {"com.telescope.canvas", "com.telescope.backend.global", "com.telescope.backend.canvas"})
@EntityScan(basePackages = "com.telescope.backend.canvas")
@EnableJpaRepositories(basePackages = "com.telescope.backend.canvas")
public class CanvasApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(CanvasApplication.class).properties("spring.config.name=canvas").run(args);
    }
}
