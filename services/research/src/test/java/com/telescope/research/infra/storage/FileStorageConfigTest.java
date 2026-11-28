package com.telescope.research.infra.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

/** 설정 값에 따라 저장소 구현체가 하나만 골라지는지. S3 클라이언트는 만들기만 하고 요청은 보내지 않는다. */
class FileStorageConfigTest {

    ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FileStorageConfig.class);

    @Test
    void 버킷이_없으면_로컬_저장소를_쓴다() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FileStorage.class);
            assertThat(context.getBean(FileStorage.class)).isInstanceOf(LocalFileStorage.class);
            assertThat(context).doesNotHaveBean(S3Client.class);
        });
    }

    @Test
    void 버킷이_빈_문자열이면_로컬_저장소를_쓴다() {
        runner.withPropertyValues("app.storage.s3.bucket=", "app.storage.s3.region=")
                .run(context -> assertThat(context.getBean(FileStorage.class)).isInstanceOf(LocalFileStorage.class));
    }

    @Test
    void 버킷과_리전이_있으면_S3_저장소를_쓴다() {
        runner.withPropertyValues("app.storage.s3.bucket=test-bucket", "app.storage.s3.region=ap-northeast-2")
                .run(context -> {
                    assertThat(context).hasSingleBean(FileStorage.class);
                    assertThat(context.getBean(FileStorage.class)).isInstanceOf(S3FileStorage.class);
                    assertThat(context).hasSingleBean(S3Client.class);
                });
    }

    @Test
    void 버킷은_있는데_리전이_없으면_기동에_실패한다() {
        runner.withPropertyValues("app.storage.s3.bucket=test-bucket")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("AWS_REGION"));
    }
}
