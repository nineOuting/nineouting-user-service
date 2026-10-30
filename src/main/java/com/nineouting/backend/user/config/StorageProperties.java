package com.nineouting.backend.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로필 사진 저장소(S3).
 *
 * @param endpoint  비우면 AWS S3. 로컬은 LocalStack 주소 (예: http://localhost:4566)
 * @param accessKey 비우면 AWS 기본 자격 증명(환경 변수·IAM 역할)을 쓴다. 로컬 LocalStack에서만 직접 넣는다
 */
@ConfigurationProperties(prefix = "nineouting.user.storage")
public record StorageProperties(String bucket, String region, String endpoint, String accessKey, String secretKey,
                                Duration uploadUrlTtl, Duration downloadUrlTtl) {

    public boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }

    public boolean hasStaticCredentials() {
        return accessKey != null && !accessKey.isBlank();
    }
}
