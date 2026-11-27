package com.telescope.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class AnalysisApplicationTests {

	@Test
	void contextLoads() {
	}

}
