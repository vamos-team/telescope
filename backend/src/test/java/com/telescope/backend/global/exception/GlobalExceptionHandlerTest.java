package com.telescope.backend.global.exception;

import com.telescope.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 비즈니스_예외는_ErrorCode의_상태와_코드로_응답한다() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.message").value("요청한 리소스를 찾을 수 없습니다."));
    }

    @Test
    void 비즈니스_예외에_직접_쓴_메시지가_있으면_그_메시지로_응답한다() throws Exception {
        mockMvc.perform(get("/test/business-detail"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("C002"))
                .andExpect(jsonPath("$.error.message").value("ID 42번 문서를 찾을 수 없습니다."));
    }

    @Test
    void 예상하지_못한_예외는_500과_일반_메시지로_응답한다() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("C999"))
                .andExpect(jsonPath("$.error.message").value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    void 입력값_검증에_실패하면_400과_필드_메시지로_응답한다() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"))
                .andExpect(jsonPath("$.error.message").value("name: 이름은 필수입니다"));
    }

    @Test
    void JSON_형식이_깨지면_400과_공통_포맷으로_응답한다() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    void 허용하지_않는_메서드로_요청하면_405와_공통_포맷으로_응답한다() throws Exception {
        mockMvc.perform(post("/test/success"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("C003"));
    }

    @Test
    void 성공하면_data에_값이_있고_error는_null이다() throws Exception {
        mockMvc.perform(get("/test/success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("안녕"))
                .andExpect(jsonPath("$.error").value(nullValue()));
    }

    @RestController
    static class TestController {

        record TestRequest(@NotBlank(message = "이름은 필수입니다") String name) {
        }

        @GetMapping("/test/business")
        public String business() {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @GetMapping("/test/business-detail")
        public String businessDetail() {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "ID 42번 문서를 찾을 수 없습니다.");
        }

        @GetMapping("/test/unexpected")
        public String unexpected() {
            throw new RuntimeException("DB 비밀번호가 틀렸습니다");
        }

        @PostMapping("/test/validation")
        public String validation(@Valid @RequestBody TestRequest request) {
            return "ok";
        }

        @GetMapping("/test/success")
        public ApiResponse<String> success() {
            return ApiResponse.success("안녕");
        }
    }
}