package com.telescope.backend.canvas.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 캔버스 JSONB 컬럼(카드 필드, 이벤트 내용, 저장한 응답)과 자바 값 사이를 바꾼다.
 *
 * <p>엔티티는 JSONB를 문자열로 들고 있고, 변환은 여기서만 한다. Hibernate의 JSON 변환기에 기대지 않아
 * Jackson 버전(Spring Boot 4는 Jackson 3)에 따른 매핑 차이를 피한다.
 */
final class CanvasJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private CanvasJson() {
    }

    /** 값을 JSON 문자열로 바꾼다. */
    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** JSON 객체 문자열을 순서를 지키는 Map으로 읽는다. 비어 있으면 빈 Map. */
    static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(json, MAP);
    }

    /** JSON 문자열을 주어진 타입으로 읽는다. 저장해 둔 응답을 되살릴 때 쓴다. */
    static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }
}
