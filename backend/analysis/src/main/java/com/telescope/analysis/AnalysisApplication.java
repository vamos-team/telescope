package com.telescope.analysis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// auth·canvas 서비스로 트래픽을 넘기기 전까지 analysis도 user·canvas 기능을 함께 띄운다
@SpringBootApplication(scanBasePackages = {
        "com.telescope.analysis", "com.telescope.common",
        "com.telescope.auth.user", "com.telescope.canvas.canvas", "com.telescope.canvas.global"})
@AutoConfigurationPackage(basePackages = {"com.telescope.analysis", "com.telescope.auth.user", "com.telescope.canvas.canvas"})
public class AnalysisApplication {

	public static void main(String[] args) {
		SpringApplication.run(AnalysisApplication.class, args);
	}

}
