package com.telescope.research;

import com.telescope.common.test.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfig.class)
class ResearchApplicationTests {

	@Test
	void contextLoads() {
	}

}
