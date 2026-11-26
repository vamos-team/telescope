package com.telescope.backend.infra.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * {@link FileStorage} 구현체를 고른다.
 *
 * <ul>
 *   <li>{@code app.storage.s3.bucket}(환경변수 {@code S3_BUCKET})이 있으면 {@link S3FileStorage}.
 *       배포 환경은 {@code s3-config} ConfigMap이 값을 넣는다.</li>
 *   <li>없거나 비어 있으면 {@link LocalFileStorage}. 로컬 개발과 테스트는 실제 S3에 붙지 않는다.</li>
 * </ul>
 */
@Configuration
public class FileStorageConfig {

    static final String BUCKET_PROPERTY = "app.storage.s3.bucket";

    /**
     * S3 클라이언트를 만든다. 리전은 {@code AWS_REGION}, 자격증명은 SDK 기본 체인(배포는 IRSA 웹 ID 토큰)이다.
     *
     * @throws IllegalStateException 버킷은 있는데 리전이 비어 있을 때. 잘못된 배포 설정을 기동 시점에 알린다
     */
    @Bean(destroyMethod = "close")
    @Conditional(S3BucketConfigured.class)
    S3Client s3Client(@Value("${app.storage.s3.region:}") String region) {
        if (!StringUtils.hasText(region)) {
            throw new IllegalStateException("S3_BUCKET이 설정됐지만 AWS_REGION이 비어 있습니다.");
        }
        return S3Client.builder().region(Region.of(region)).build();
    }

    /** 버킷이 설정됐을 때 S3 저장소를 등록한다. */
    @Bean
    @Conditional(S3BucketConfigured.class)
    FileStorage s3FileStorage(S3Client s3Client, @Value("${" + BUCKET_PROPERTY + "}") String bucket) {
        return new S3FileStorage(s3Client, bucket);
    }

    /** 버킷이 없을 때 로컬 디스크 저장소를 등록한다. */
    @Bean
    @Conditional(S3BucketNotConfigured.class)
    FileStorage localFileStorage(@Value("${app.storage.local.base-dir:./data/files}") String baseDir) {
        return new LocalFileStorage(baseDir);
    }

    /**
     * 버킷 값이 비어 있지 않은지. {@code @ConditionalOnProperty}는 빈 문자열도 "있음"으로 보므로
     * ({@code S3_BUCKET:} 기본값이 빈 문자열) 직접 확인한다.
     */
    static boolean bucketConfigured(ConditionContext context) {
        return StringUtils.hasText(context.getEnvironment().getProperty(BUCKET_PROPERTY));
    }

    /** 버킷이 설정됐을 때 참. */
    static class S3BucketConfigured implements Condition {
        /** 버킷 값이 있으면 S3 쪽 빈을 등록한다. */
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return bucketConfigured(context);
        }
    }

    /** 버킷이 없을 때 참. */
    static class S3BucketNotConfigured implements Condition {
        /** 버킷 값이 없으면 로컬 저장소 빈을 등록한다. */
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !bucketConfigured(context);
        }
    }
}
