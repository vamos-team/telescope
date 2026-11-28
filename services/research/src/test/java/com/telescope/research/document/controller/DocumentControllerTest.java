package com.telescope.research.document.controller;

import com.telescope.research.document.dto.DocumentResponse;
import com.telescope.research.document.dto.DocumentUploadCommand;
import com.telescope.research.document.dto.DocumentUploadResponse;
import com.telescope.research.document.dto.UploadRejection;
import com.telescope.research.document.service.DocumentService;
import com.telescope.common.auth.CurrentUserProvider;
import com.telescope.common.auth.UnauthenticatedCurrentUserProvider;
import com.telescope.common.exception.BusinessException;
import com.telescope.common.exception.ErrorCode;
import com.telescope.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 업로드·조회 요청 형식과 응답 코드. 서비스는 목으로 둔다. */
class DocumentControllerTest {

    DocumentService documentService = Mockito.mock(DocumentService.class);

    MockMvc mvc(CurrentUserProvider user) {
        return MockMvcBuilders.standaloneSetup(new DocumentController(documentService, user))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    MockMvc loggedIn() {
        return mvc(() -> 7L);
    }

    MockMultipartFile pdf() {
        return new MockMultipartFile("file", "예산서.pdf", "application/pdf", "abc".getBytes());
    }

    @Test
    void 업로드는_202와_작업_ID_문서_ID를_돌려준다() throws Exception {
        when(documentService.upload(eq(7L), any(DocumentUploadCommand.class), any(InputStream.class)))
                .thenReturn(new DocumentUploadResponse(11L, List.of(21L)));

        loggedIn().perform(multipart("/api/v1/documents/uploads").file(pdf())
                        .param("canvasId", "3").param("company", "SKT").param("publishedDate", "2026-06-30"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.jobId").value(11))
                .andExpect(jsonPath("$.data.documentIds[0]").value(21));

        ArgumentCaptor<DocumentUploadCommand> command = ArgumentCaptor.forClass(DocumentUploadCommand.class);
        verify(documentService).upload(eq(7L), command.capture(), any(InputStream.class));
        assertThat(command.getValue()).isEqualTo(
                new DocumentUploadCommand(3L, "SKT", LocalDate.of(2026, 6, 30), "예산서.pdf", 3));
    }

    @Test
    void 발행일은_생략할_수_있다() throws Exception {
        when(documentService.upload(eq(7L), any(DocumentUploadCommand.class), any(InputStream.class)))
                .thenReturn(new DocumentUploadResponse(11L, List.of(21L)));

        loggedIn().perform(multipart("/api/v1/documents/uploads").file(pdf())
                        .param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<DocumentUploadCommand> command = ArgumentCaptor.forClass(DocumentUploadCommand.class);
        verify(documentService).upload(eq(7L), command.capture(), any(InputStream.class));
        assertThat(command.getValue().publishedDate()).isNull();
    }

    @Test
    void 인증_구현이_붙기_전에는_401_A002이고_서비스를_부르지_않는다() throws Exception {
        mvc(new UnauthenticatedCurrentUserProvider())
                .perform(multipart("/api/v1/documents/uploads").file(pdf())
                        .param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("A002"));
        verify(documentService, never()).upload(any(), any(), any());
    }

    @Test
    void 파일이나_캔버스가_빠지면_400() throws Exception {
        loggedIn().perform(multipart("/api/v1/documents/uploads").param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isBadRequest());
        loggedIn().perform(multipart("/api/v1/documents/uploads").file(pdf()).param("company", "SKT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 업로드_거절은_400_D001과_data_reason으로_알린다() throws Exception {
        when(documentService.upload(eq(7L), any(DocumentUploadCommand.class), any(InputStream.class)))
                .thenThrow(new BusinessException(ErrorCode.DOCUMENT_UPLOAD_REJECTED, "지원하지 않는 형식입니다(.exe).",
                        UploadRejection.format()));

        loggedIn().perform(multipart("/api/v1/documents/uploads").file(pdf())
                        .param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("D001"))
                .andExpect(jsonPath("$.error.message").value("지원하지 않는 형식입니다(.exe)."))
                .andExpect(jsonPath("$.data.reason").value("FORMAT_MISMATCH"));
    }

    @Test
    void 편집_권한이_없으면_403_K002() throws Exception {
        when(documentService.upload(eq(7L), any(DocumentUploadCommand.class), any(InputStream.class)))
                .thenThrow(new BusinessException(ErrorCode.CANVAS_EDIT_DENIED));

        loggedIn().perform(multipart("/api/v1/documents/uploads").file(pdf())
                        .param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("K002"));
    }

    @Test
    void 크기_상한을_넘으면_413과_상한을_알린다() throws Exception {
        when(documentService.upload(eq(7L), any(DocumentUploadCommand.class), any(InputStream.class)))
                .thenThrow(new MaxUploadSizeExceededException(50L * 1024 * 1024));

        loggedIn().perform(multipart("/api/v1/documents/uploads").file(pdf())
                        .param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.code").value("C001"))
                .andExpect(jsonPath("$.error.message").value("파일이 너무 큽니다 (최대 50MB)."));
    }

    @Test
    void 컨테이너가_상한을_알려_주지_않으면_설정_값으로_알린다() throws Exception {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        handler.setMaxFileSize("50MB");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DocumentController(documentService, () -> 7L))
                .setControllerAdvice(handler).build();
        when(documentService.upload(eq(7L), any(DocumentUploadCommand.class), any(InputStream.class)))
                .thenThrow(new MaxUploadSizeExceededException(-1));

        mvc.perform(multipart("/api/v1/documents/uploads").file(pdf()).param("canvasId", "3").param("company", "SKT"))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.error.message").value("파일이 너무 큽니다 (최대 50MB)."));
    }

    @Test
    void 문서_조회는_카드_데이터를_돌려준다() throws Exception {
        when(documentService.get(7L, 21L)).thenReturn(new DocumentResponse(21L, "internal", "SKT", "예산서", "사내 업로드",
                LocalDate.of(2026, 6, 30), "요약", List.of(), List.of("capex"), "", true));

        loggedIn().perform(get("/api/v1/documents/21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("internal"))
                .andExpect(jsonPath("$.data.date").value("2026-06-30"))
                .andExpect(jsonPath("$.data.tags[0]").value("capex"))
                .andExpect(jsonPath("$.data.uploaded").value(true));
    }
}
