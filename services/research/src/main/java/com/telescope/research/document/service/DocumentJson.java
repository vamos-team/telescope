package com.telescope.research.document.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 파싱 결과 JSONB와 자바 값 사이를 바꾼다. 엔티티는 JSON을 문자열로 들고 있고 변환은 여기서만 한다
 * (캔버스의 {@code CanvasJson}과 같은 방식).
 */
final class DocumentJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private DocumentJson() {
    }

    /** 값을 JSON 문자열로 바꾼다. */
    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** JSON 문자열을 Map으로 읽는다. 객체가 아니거나 비어 있으면 빈 Map. */
    static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object value = MAPPER.readValue(json, Object.class);
        if (value instanceof Map<?, ?>) {
            return MAPPER.readValue(json, MAP);
        }
        return new LinkedHashMap<>();
    }
}
