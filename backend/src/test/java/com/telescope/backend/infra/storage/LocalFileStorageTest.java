package com.telescope.backend.infra.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void 저장한_파일을_key로_다시_읽고_지울_수_있다() throws IOException {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        byte[] content = "hello".getBytes();

        String key = storage.store("report.pdf", new ByteArrayInputStream(content));
        assertThat(key).endsWith(".pdf");

        Resource loaded = storage.load(key);
        assertThat(loaded.getContentAsByteArray()).isEqualTo(content);

        storage.delete(key);
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void base_dir를_벗어나는_key는_거부한다() {
        LocalFileStorage storage = new LocalFileStorage(tempDir.toString());
        assertThatThrownBy(() -> storage.load("../outside"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
