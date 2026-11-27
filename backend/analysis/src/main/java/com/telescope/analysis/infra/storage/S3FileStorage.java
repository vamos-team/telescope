package com.telescope.analysis.infra.storage;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * S3 버킷에 파일을 저장하는 {@link FileStorage} 구현. 배포 환경에서 쓴다.
 *
 * <p>키 형식은 {@link LocalFileStorage}와 같고({@link StorageKeys}), 버킷 최상위에 그대로 둔다.
 * 빈 등록은 {@link FileStorageConfig}가 한다(S3 버킷이 설정됐을 때만).
 */
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final String bucket;

    /** 사용할 S3 클라이언트와 버킷 이름을 받는다. */
    public S3FileStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /**
     * 파일을 버킷에 올리고 키를 돌려준다.
     *
     * <p>S3 업로드는 길이를 미리 알아야 하므로, 길이를 모르는 스트림을 메모리에 다 올리지 않도록
     * 임시 파일에 먼저 받은 뒤 올린다. 임시 파일은 성공·실패와 관계없이 지운다.
     */
    @Override
    public String store(String originalFilename, InputStream content) {
        String key = StorageKeys.newKey(originalFilename);
        Path temp = null;
        try {
            temp = Files.createTempFile("telescope-upload-", ".tmp");
            Files.copy(content, temp, StandardCopyOption.REPLACE_EXISTING);
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(temp));
        } catch (IOException e) {
            throw new UncheckedIOException("파일 저장 실패: key=" + key, e);
        } finally {
            deleteQuietly(temp);
        }
        return key;
    }

    /**
     * 키의 객체를 스트림으로 읽어 온다. 디스크에 내려받지 않는다.
     *
     * <p>돌려준 {@link Resource}는 한 번만 읽을 수 있고, 호출자가 다 읽거나 닫아야 연결이 반환된다.
     *
     * @throws UncheckedIOException 키에 해당하는 객체가 없을 때
     */
    @Override
    public Resource load(String key) {
        ResponseInputStream<GetObjectResponse> stream;
        try {
            stream = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new UncheckedIOException(new IOException("파일이 없습니다: key=" + key, e));
        }
        long length = stream.response().contentLength() == null ? -1 : stream.response().contentLength();
        return new InputStreamResource(stream, "S3 객체 [" + key + "]") {
            /** 길이를 S3 응답에서 가져온다. 기본 구현은 스트림을 끝까지 읽어 길이를 재므로 쓰지 않는다. */
            @Override
            public long contentLength() throws IOException {
                return length >= 0 ? length : super.contentLength();
            }
        };
    }

    /** 키의 객체를 지운다. S3는 없는 키를 지워도 성공으로 응답하므로 따로 확인하지 않는다(멱등). */
    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    /** 임시 파일을 지운다. 지우지 못해도 저장 결과에는 영향이 없으므로 예외를 내지 않는다. */
    private static void deleteQuietly(Path temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // OS 임시 디렉터리 정리에 맡긴다
        }
    }
}
