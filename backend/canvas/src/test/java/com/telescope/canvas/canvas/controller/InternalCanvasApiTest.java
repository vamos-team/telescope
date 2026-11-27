package com.telescope.canvas.canvas.controller;

import com.telescope.canvas.CanvasApplication;
import com.telescope.common.auth.InternalApiKeyFilter;
import com.telescope.common.test.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 내부 캔버스 권한 API(InternalCanvasController). 내부 키 확인과 없는 캔버스 404 C002를 본다. */
@SpringBootTest(classes = CanvasApplication.class, properties = "app.internal-api-key=test-internal-key")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class InternalCanvasApiTest {

    private static final String KEY = "test-internal-key";

    @Autowired MockMvc mvc;

    @Test
    void 내부_키가_없으면_401() throws Exception {
        mvc.perform(get("/internal/v1/canvases/1/access").param("userId", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    @Test
    void 없는_캔버스의_권한_확인은_404_C002() throws Exception {
        mvc.perform(get("/internal/v1/canvases/987654321/access").param("userId", "1").header(InternalApiKeyFilter.HEADER, KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"));
    }
}
