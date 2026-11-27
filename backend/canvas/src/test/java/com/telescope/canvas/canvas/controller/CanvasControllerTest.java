package com.telescope.canvas.canvas.controller;

import com.telescope.canvas.canvas.dto.CanvasEventsRequest;
import com.telescope.canvas.canvas.dto.CanvasEventsResponse;
import com.telescope.canvas.canvas.dto.TextConflict;
import com.telescope.canvas.canvas.service.CanvasEventService;
import com.telescope.canvas.canvas.service.CanvasService;
import com.telescope.common.auth.CurrentUserProvider;
import com.telescope.common.auth.UnauthenticatedCurrentUserProvider;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 상태와 응답 모양(ApiResponse, 에러 코드, 충돌 시 data)을 확인한다. 저장 규칙은 서비스 테스트에서 본다. */
class CanvasControllerTest {

    CanvasService canvasService = Mockito.mock(CanvasService.class);
    CanvasEventService eventService = Mockito.mock(CanvasEventService.class);

    MockMvc mvc(CurrentUserProvider user) {
        return MockMvcBuilders.standaloneSetup(new CanvasController(canvasService, eventService, user))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    MockMvc loggedIn() {
        return mvc(() -> 7L);
    }

    @Test
    void 인증_구현이_붙기_전에는_401_A002() throws Exception {
        mvc(new UnauthenticatedCurrentUserProvider()).perform(get("/api/v1/canvases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
    }

    @Test
    void 이벤트_저장_성공은_lastSeq와_글_버전을_돌려준다() throws Exception {
        when(eventService.apply(eq(1L), eq(7L), any(CanvasEventsRequest.class)))
                .thenReturn(new CanvasEventsResponse(42, "L2_workspace", Map.of()));

        loggedIn().perform(post("/api/v1/canvases/1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"events\":[{\"type\":\"move_card\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastSeq").value(42))
                .andExpect(jsonPath("$.data.firstChangedLayer").value("L2_workspace"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void 글_충돌은_409_K003과_현재_글을_data에_담는다() throws Exception {
        UUID card = UUID.randomUUID();
        when(eventService.apply(eq(1L), eq(7L), any(CanvasEventsRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.CANVAS_TEXT_CONFLICT, "다른 사람이 먼저 글을 고쳤습니다.",
                        new TextConflict(card, "memo", "먼저 고친 글", 3)));

        loggedIn().perform(post("/api/v1/canvases/1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"events\":[{\"type\":\"set_card_memo\"}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("K003"))
                .andExpect(jsonPath("$.data.targetId").value(card.toString()))
                .andExpect(jsonPath("$.data.text").value("먼저 고친 글"))
                .andExpect(jsonPath("$.data.version").value(3));
    }

    @Test
    void requestId나_events가_없으면_400_C001() throws Exception {
        loggedIn().perform(post("/api/v1/canvases/1/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("C001"));
    }

    @Test
    void 권한이_없으면_403_K001() throws Exception {
        when(canvasService.open(1L, 7L)).thenThrow(new BusinessException(ErrorCode.CANVAS_ACCESS_DENIED));

        loggedIn().perform(get("/api/v1/canvases/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("K001"));
    }

    @Test
    void 삭제는_본문_없이_204() throws Exception {
        loggedIn().perform(delete("/api/v1/canvases/1"))
                .andExpect(status().isNoContent());
        Mockito.verify(canvasService).delete(1L, 7L);
    }
}
