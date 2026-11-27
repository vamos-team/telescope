package com.telescope.analysis.infra.storage;

import java.util.UUID;

/** 저장 구현체가 함께 쓰는 키 생성 규칙. 로컬과 S3의 키 형식을 같게 유지해 구현체를 바꿔도 키 모양이 같다. */
final class StorageKeys {

    private StorageKeys() {
    }

    /**
     * 새 저장 키를 만든다. 형식은 {@code <UUID><원본 확장자>}(예: {@code 3f…9a.pdf}).
     * 원본 파일명은 확장자만 쓰고 경로 결정에 그대로 쓰지 않는다.
     */
    static String newKey(String originalFilename) {
        return UUID.randomUUID() + extensionOf(originalFilename);
    }

    /** 원본 파일명의 마지막 점부터의 확장자를 돌려준다. 없으면 빈 문자열. */
    private static String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        return dotIndex >= 0 ? originalFilename.substring(dotIndex) : "";
    }
}
