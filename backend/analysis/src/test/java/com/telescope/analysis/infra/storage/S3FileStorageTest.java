package com.telescope.analysis.infra.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S3 저장소의 저장·읽기·삭제 규약. 실제 S3에 붙지 않고 {@link S3Client}를 목으로 둔다. */
class S3FileStorageTest {

    private static final String BUCKET = "test-bucket";

    S3Client s3 = mock(S3Client.class);
    S3FileStorage storage = new S3FileStorage(s3, BUCKET);

    @Test
    void 저장하면_버킷에_같은_키_형식으로_내용을_올리고_키를_돌려준다() {
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(s3.putObject(request.capture(), any(RequestBody.class))).thenAnswer(inv -> {
            // 임시 파일은 업로드 뒤 지워지므로 호출 시점에 내용을 읽어 둔다
            try (InputStream in = inv.<RequestBody>getArgument(1).contentStreamProvider().newStream()) {
                uploaded.set(in.readAllBytes());
            }
            return PutObjectResponse.builder().build();
        });

        String key = storage.store("report.pdf", new ByteArrayInputStream("hello".getBytes()));

        assertThat(key).matches("[0-9a-f-]{36}\\.pdf");
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(key);
        assertThat(uploaded.get()).isEqualTo("hello".getBytes());
    }

    @Test
    void 읽으면_객체_내용과_길이를_스트림으로_돌려준다() throws IOException {
        byte[] content = "hello".getBytes();
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) content.length).build(),
                AbortableInputStream.create(new ByteArrayInputStream(content))));

        Resource loaded = storage.load("abc.pdf");

        assertThat(loaded.contentLength()).isEqualTo(content.length);
        assertThat(loaded.getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void 없는_키를_읽으면_인터페이스_규약대로_UncheckedIOException() {
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(NoSuchKeyException.builder().build());

        assertThatThrownBy(() -> storage.load("missing.pdf"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("missing.pdf");
    }

    @Test
    void 삭제는_버킷과_키로_요청한다() {
        storage.delete("abc.pdf");

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("abc.pdf");
    }
}
