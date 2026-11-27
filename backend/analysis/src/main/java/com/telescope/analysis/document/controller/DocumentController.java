package com.telescope.analysis.document.controller;

import com.telescope.analysis.document.dto.DocumentResponse;
import com.telescope.analysis.document.dto.DocumentUploadCommand;
import com.telescope.analysis.document.dto.DocumentUploadResponse;
import com.telescope.analysis.document.service.DocumentService;
import com.telescope.common.auth.CurrentUserProvider;
import com.telescope.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;

/**
 * 문서 업로드와 조회. 규약은 {@code docs/backend/api-spec.md}의 문서 업로드 절.
 */
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final CurrentUserProvider currentUser;

    /**
     * 캔버스에 파일 하나를 올린다({@code multipart/form-data}). 자료 준비가 끝나면 {@code GET /documents/{id}}로 카드 데이터를 받는다.
     *
     * @param file          파일 하나
     * @param canvasId      문서가 속할 캔버스
     * @param company       회사
     * @param publishedDate 발행일({@code yyyy-MM-dd}). 없으면 올린 날
     */
    @PostMapping(path = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<DocumentUploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("canvasId") Long canvasId,
            @RequestParam("company") String company,
            @RequestParam(value = "publishedDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishedDate) {
        Long userId = currentUser.currentUserId();
        DocumentUploadCommand command = new DocumentUploadCommand(
                canvasId, company, publishedDate, file.getOriginalFilename(), file.getSize());
        try (InputStream content = file.getInputStream()) {
            return ApiResponse.success(documentService.upload(userId, command, content));
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 파일을 읽지 못했습니다.", e);
        }
    }

    /** 문서 하나. 업로드 문서는 그 캔버스 권한자만 본다. */
    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse> get(@PathVariable Long id) {
        return ApiResponse.success(documentService.get(currentUser.currentUserId(), id));
    }
}
