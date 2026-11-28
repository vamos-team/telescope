package com.telescope.research.document.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 업로드 설정({@code app.document.upload}).
 *
 * <p>허용 확장자는 업로드 정책이 정해지기 전까지 ai 파서가 읽는 형식으로 임시 제한한다(루트 {@code AGENTS.md} 7장 미정).
 * 대상 회사는 지금 4곳이지만 늘어날 수 있어 설정으로 둔다. 크기 상한은 {@code spring.servlet.multipart.max-file-size}가 맡는다.
 *
 * @param allowedExtensions 허용 확장자(점 없이, 소문자로 비교)
 * @param companies         업로드 대상 회사. 사용자가 이 중에서 고른다. 저장은 이 목록의 표기 그대로 한다
 */
@ConfigurationProperties(prefix = "app.document.upload")
public record DocumentUploadProperties(List<String> allowedExtensions, List<String> companies) {

    /** 목록이 비면 아무것도 받지 않는다(설정 누락을 "모두 허용"으로 해석하지 않는다). */
    public DocumentUploadProperties {
        allowedExtensions = allowedExtensions == null ? List.of()
                : allowedExtensions.stream().map(e -> e.strip().toLowerCase(Locale.ROOT)).toList();
        companies = companies == null ? List.of() : companies.stream().map(String::strip).toList();
    }

    /** 확장자(점 없이)가 허용 목록에 있는지. */
    boolean allows(String extension) {
        return extension != null && allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
    }

    /** 고른 회사를 목록의 표기로 돌려준다. 대소문자와 앞뒤 공백은 무시한다. 목록에 없으면 비어 있다. */
    Optional<String> company(String selected) {
        if (selected == null) {
            return Optional.empty();
        }
        String wanted = selected.strip();
        return companies.stream().filter(c -> c.equalsIgnoreCase(wanted)).findFirst();
    }
}
