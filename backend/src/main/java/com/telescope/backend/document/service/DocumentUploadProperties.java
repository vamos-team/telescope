package com.telescope.backend.document.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * 업로드 설정({@code app.document.upload}).
 *
 * <p>허용 확장자는 업로드 정책이 정해지기 전까지 ai 파서가 읽는 형식으로 임시 제한한다(루트 {@code CLAUDE.md} 7장 미정).
 * 크기 상한은 {@code spring.servlet.multipart.max-file-size}가 맡는다.
 *
 * @param allowedExtensions 허용 확장자(점 없이, 소문자로 비교)
 */
@ConfigurationProperties(prefix = "app.document.upload")
public record DocumentUploadProperties(List<String> allowedExtensions) {

    /** 목록이 비면 아무것도 받지 않는다(설정 누락을 "모두 허용"으로 해석하지 않는다). */
    public DocumentUploadProperties {
        allowedExtensions = allowedExtensions == null ? List.of()
                : allowedExtensions.stream().map(e -> e.toLowerCase(Locale.ROOT)).toList();
    }

    /** 확장자(점 없이)가 허용 목록에 있는지. */
    boolean allows(String extension) {
        return extension != null && allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
    }
}
