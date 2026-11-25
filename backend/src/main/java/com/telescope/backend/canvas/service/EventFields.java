package com.telescope.backend.canvas.service;

import com.telescope.backend.global.exception.BusinessException;
import com.telescope.backend.global.exception.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 이벤트 한 건(JSON 객체를 읽은 Map)에서 필드를 꺼내며 형식을 확인한다.
 * 형식이 틀리면 {@code 400 C001}과 함께 몇 번째 이벤트의 어느 필드인지 알려 준다.
 */
final class EventFields {

    private final Map<String, Object> event;
    private final int index;

    EventFields(Map<String, Object> event, int index) {
        this.event = event;
        this.index = index;
    }

    /** 이벤트 종류. 없으면 400. */
    String type() {
        return string("type");
    }

    /** 필수 문자열. 없거나 문자열이 아니면 400. 빈 문자열은 허용한다. */
    String string(String name) {
        Object v = event.get(name);
        if (v instanceof String s) {
            return s;
        }
        throw invalid(name, "문자열이어야 합니다");
    }

    /** 선택 문자열. 없거나 null이면 null. */
    String optionalString(String name) {
        Object v = event.get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw invalid(name, "문자열이어야 합니다");
    }

    /** 필수 UUID(카드·연결 ID). */
    UUID uuid(String name) {
        String s = string(name);
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw invalid(name, "UUID 형식이어야 합니다");
        }
    }

    /** 필수 정수. */
    int integer(String name) {
        Object v = event.get(name);
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
            return n.intValue();
        }
        throw invalid(name, "정수여야 합니다");
    }

    /** 선택 실수(좌표). 없으면 기본값. */
    double number(String name, double defaultValue) {
        Object v = event.get(name);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        throw invalid(name, "숫자여야 합니다");
    }

    /** 필수 실수(좌표). */
    double number(String name) {
        if (!event.containsKey(name)) {
            throw invalid(name, "숫자여야 합니다");
        }
        return number(name, 0);
    }

    /** 숫자 또는 null인 값(표 칸 수정값). 키가 없으면 400. */
    Number nullableNumber(String name) {
        if (!event.containsKey(name)) {
            throw invalid(name, "숫자 또는 null이어야 합니다");
        }
        Object v = event.get(name);
        if (v == null || v instanceof Number) {
            return (Number) v;
        }
        throw invalid(name, "숫자 또는 null이어야 합니다");
    }

    /** 필수 불리언. */
    boolean bool(String name) {
        Object v = event.get(name);
        if (v instanceof Boolean b) {
            return b;
        }
        throw invalid(name, "true 또는 false여야 합니다");
    }

    /** 필수 정수 목록(행·열 번호). */
    List<Integer> integers(String name) {
        Object v = event.get(name);
        if (!(v instanceof List<?> list)) {
            throw invalid(name, "정수 목록이어야 합니다");
        }
        List<Integer> result = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) {
                throw invalid(name, "정수 목록이어야 합니다");
            }
            result.add(n.intValue());
        }
        return result;
    }

    /** 필수 UUID 목록(비교 원본 카드). */
    List<UUID> uuids(String name) {
        Object v = event.get(name);
        if (!(v instanceof List<?> list)) {
            throw invalid(name, "UUID 목록이어야 합니다");
        }
        List<UUID> result = new ArrayList<>();
        for (Object o : list) {
            try {
                result.add(UUID.fromString(String.valueOf(o)));
            } catch (IllegalArgumentException e) {
                throw invalid(name, "UUID 목록이어야 합니다");
            }
        }
        return result;
    }

    /** 필수 목록(형식을 따지지 않음. 계정 대응의 칸 목록). */
    List<?> list(String name) {
        Object v = event.get(name);
        if (v instanceof List<?> list) {
            return list;
        }
        throw invalid(name, "목록이어야 합니다");
    }

    /** 값 그대로(정렬 키처럼 문자열·숫자·null이 모두 올 수 있는 값). */
    Object raw(String name) {
        return event.get(name);
    }

    /** 이 이벤트의 형식 오류를 만든다. 메시지는 {@code events[번호].필드: 사유}. */
    BusinessException invalid(String name, String reason) {
        return new BusinessException(ErrorCode.INVALID_INPUT, "events[" + index + "]." + name + ": " + reason);
    }
}
