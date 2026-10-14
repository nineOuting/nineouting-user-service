package com.nineouting.backend.user.service;

import com.nineouting.backend.user.config.StorageProperties;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 프로필 사진은 서버를 거치지 않고 브라우저가 S3에 직접 올린다(presigned URL).
 * 버킷은 비공개이고, 사진을 보여 줄 때도 일정 시간만 유효한 주소를 만든다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileImageStorage {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties properties;

    public String uploadUrl(String key, String contentType) {
        return s3Presigner.presignPutObject(request -> request
                        .signatureDuration(properties.uploadUrlTtl())
                        .putObjectRequest(put -> put.bucket(properties.bucket()).key(key).contentType(contentType)))
                .url().toString();
    }

    public String downloadUrl(String key) {
        return s3Presigner.presignGetObject(request -> request
                        .signatureDuration(properties.downloadUrlTtl())
                        .getObjectRequest(get -> get.bucket(properties.bucket()).key(key)))
                .url().toString();
    }

    /** 올라간 파일의 크기(바이트). 아직 업로드되지 않았으면 빈 값. */
    public Optional<Long> size(String key) {
        try {
            return Optional.of(s3Client.headObject(head -> head.bucket(properties.bucket()).key(key)).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    /** 지우기 실패는 사용자 요청을 막을 이유가 없으므로 기록만 남긴다. */
    public void deleteQuietly(String key) {
        try {
            s3Client.deleteObject(delete -> delete.bucket(properties.bucket()).key(key));
        } catch (SdkException e) {
            log.warn("프로필 사진 삭제 실패: {}", key, e);
        }
    }
}
