package com.telescope.canvas;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication(scanBasePackages = {"com.telescope.canvas", "com.telescope.common"})
public class CanvasApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(CanvasApplication.class).properties("spring.config.name=canvas").run(args);
    }
}
