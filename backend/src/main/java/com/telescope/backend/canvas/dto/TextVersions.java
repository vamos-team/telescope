package com.telescope.backend.canvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 카드·연결의 글 버전. 이번 묶음에서 바뀐 글만 채우고 나머지는 응답에서 뺀다.
 *
 * @param text   프롬프트 카드 본문 버전
 * @param prompt 카드 프롬프트 또는 선 프롬프트 버전
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TextVersions(Integer text, Integer prompt) {

    /** 본문 버전을 새 값으로 바꾼 사본을 돌려준다. */
    public TextVersions withText(int version) {
        return new TextVersions(version, prompt);
    }

    /** 프롬프트 버전을 새 값으로 바꾼 사본을 돌려준다. */
    public TextVersions withPrompt(int version) {
        return new TextVersions(text, version);
    }
}
