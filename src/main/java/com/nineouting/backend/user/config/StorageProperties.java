package com.nineouting.backend.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로필 사진 저장소(S3).
 *
 * @param endpoint  비우면 AWS S3. 로컬은 LocalStack 주소 (예: http://localhost:4566)
 * @param presignEndpoint 브라우저에 줄 주소만 다를 때 (로컬 통합 환경: 서비스는 localstack:4566, 브라우저는 localhost:4566). 비우면 endpoint
 * @param accessKey 비우면 AWS 기본 자격 증명(환경 변수·IAM 역할)을 쓴다. 로컬 LocalStack에서만 직접 넣는다
 */
@ConfigurationProperties(prefix = "nineouting.user.storage")
public record StorageProperties(String bucket, String region, String endpoint, String presignEndpoint,
                                String accessKey, String secretKey,
                                Duration uploadUrlTtl, Duration downloadUrlTtl) {

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }

    /** 브라우저가 쓸 주소(업로드, 내려받기)를 만들 때의 S3 주소. 없으면 endpoint, 둘 다 없으면 null(AWS S3) */
    public String presignEndpointOrDefault() {
        return presignEndpoint != null && !presignEndpoint.isBlank() ? presignEndpoint : endpoint;
    }

    public boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank();
    }
}
