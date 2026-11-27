package com.telescope.backend.infra.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 파일 저장소 접근 인터페이스.
 *
 * <p>기능 패키지는 이 인터페이스에만 의존하고, 구현체({@link LocalFileStorage} 또는
 * 이후 추가될 클라우드 스토리지 구현체)는 설정으로 교체한다.
 */
public interface FileStorage {

    /**
     * 파일을 저장하고, 이후 {@link #load}/{@link #delete}에 쓸 key를 반환한다.
     *
     * @param originalFilename 원본 파일명 (확장자 보존 등에 참고용. 저장 경로 결정에 그대로 쓰지 않는다)
     * @param content 파일 내용. 이 메서드가 다 읽은 뒤의 스트림 상태는 호출자가 책임지지 않는다
     */
    String store(String originalFilename, InputStream content);

    /**
     * key로 저장된 파일을 읽어온다.
     *
     * @throws java.io.UncheckedIOException key에 해당하는 파일이 없거나 읽을 수 없으면 던진다
     *         (TODO: 전용 예외로 교체 검토, global 패키지의 BusinessException/ErrorCode 확정 후)
     */
    Resource load(String key);

    /**
     * key로 저장된 파일을 삭제한다. 이미 없는 key를 지워도 예외를 던지지 않는다(멱등).
     */
    void delete(String key);
}
