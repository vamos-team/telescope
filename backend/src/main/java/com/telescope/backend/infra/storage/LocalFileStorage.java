package com.telescope.backend.infra.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 로컬 디스크에 파일을 저장하는 {@link FileStorage} 구현.
 *
 * <p>저장 위치는 {@code app.storage.local.base-dir} 설정(환경변수 {@code FILE_STORAGE_DIR}로
 * 오버라이드 가능)으로 정한다. 하드코딩하지 않는다(CLAUDE.md "설정 값은 코드에 박지 않는다" 원칙).
 *
 * <p>TODO: 기본값 {@code ./data/files}는 "로컬에서 ./gradlew bootRun으로 돌릴 때" 기준이다.
 * docker-compose의 backend 컨테이너는 {@code ./data}를 {@code /app/data}에 마운트하므로,
 * 컨테이너 안에서 이 기본값을 그대로 쓰면 {@code /app/data/files}가 아니라
 * {@code backend 프로세스의 현재 작업 디렉터리 기준} 상대 경로로 풀려서 의도와 다른 곳에 쓸 수 있다.
 * docker-compose.yml의 backend 서비스에 {@code FILE_STORAGE_DIR=/app/data/files} 같은 값을
 * 명시로 넣어줘야 하는지 확인 필요 (이슈 #10 범위 밖이라 여기서는 안 건드림).
 */
@Component
public class LocalFileStorage implements FileStorage {

    private final Path baseDir;

    public LocalFileStorage(@Value("${app.storage.local.base-dir:./data/files}") String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public String store(String originalFilename, InputStream content) {
        String extension = extensionOf(originalFilename);
        String key = UUID.randomUUID() + extension;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장 실패: key=" + key, e);
        }
        return key;
    }

    @Override
    public Resource load(String key) {
        Path path = resolve(key);
        if (!Files.exists(path)) {
            throw new UncheckedIOException(new IOException("파일이 없습니다: key=" + key));
        }
        return new FileSystemResource(path);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 삭제 실패: key=" + key, e);
        }
    }

    private Path resolve(String key) {
        Path resolved = baseDir.resolve(key).normalize();
        if (!resolved.startsWith(baseDir.normalize())) {
            // key에 "../" 등이 섞여 base-dir 밖을 가리키는 경우. store()가 발급한 key만 쓰는 한
            // 일어나지 않지만, 혹시 모를 경로 탈출을 막기 위해 방어적으로 둔다.
            throw new IllegalArgumentException("잘못된 key: " + key);
        }
        return resolved;
    }

    private String extensionOf(String originalFilename) {
        if (originalFilename == null) {
            return "";
        }
        int dotIndex = originalFilename.lastIndexOf('.');
        return dotIndex >= 0 ? originalFilename.substring(dotIndex) : "";
    }
}
