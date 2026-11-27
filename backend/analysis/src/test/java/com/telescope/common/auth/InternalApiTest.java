package com.telescope.common.auth;

import com.telescope.analysis.AnalysisApplication;
import com.telescope.analysis.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AnalysisApplication.class, properties = "app.internal-api-key=test-internal-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalApiTest {

    private static final String KEY = "test-internal-key";

    @Autowired MockMvc mvc;

    @Test
    void 내부_키가_없으면_401() throws Exception {
        mvc.perform(get("/internal/v1/users/summaries").param("ids", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    @Test
    void 내부_키가_틀리면_401() throws Exception {
        mvc.perform(get("/internal/v1/users/summaries").param("ids", "1").header(InternalApiKeyFilter.HEADER, "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 사용자_요약은_내부_키로_조회되고_없는_사용자는_빠진다() throws Exception {
        mvc.perform(get("/internal/v1/users/summaries").param("ids", "987654321").header(InternalApiKeyFilter.HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void 사용자_요약은_한_번에_500명까지() throws Exception {
        String ids = LongStream.rangeClosed(1, 501).mapToObj(Long::toString).collect(Collectors.joining(","));
        mvc.perform(get("/internal/v1/users/summaries").param("ids", ids).header(InternalApiKeyFilter.HEADER, KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    void 없는_캔버스의_권한_확인은_404() throws Exception {
        mvc.perform(get("/internal/v1/canvases/987654321/access").param("userId", "1").header(InternalApiKeyFilter.HEADER, KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }
}
