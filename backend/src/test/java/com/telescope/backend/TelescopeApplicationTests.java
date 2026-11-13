package com.telescope.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class TelescopeApplicationTests {

	@Test
	void contextLoads() {
	}

}
