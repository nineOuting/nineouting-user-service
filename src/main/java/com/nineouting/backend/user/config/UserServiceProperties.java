package com.nineouting.backend.user.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param frontendBaseUrl     비밀번호 재설정 링크에 쓰는 프론트 주소
 * @param refreshCookieSecure refresh 토큰 쿠키의 Secure 속성 (HTTPS가 없는 로컬에서만 false)
 * @param logMailForLocalDev  인증 코드·재설정 링크를 로그에 남길지 (local 프로필 전용. 로그에 비밀 값이 남는다)
 */
@ConfigurationProperties(prefix = "nineouting.user")
public record UserServiceProperties(String frontendBaseUrl, boolean refreshCookieSecure, boolean logMailForLocalDev,
                                    Jwt jwt) {

    /**
     * @param privateKey        RS256 개인키 (PKCS#8 PEM). 비어 있으면 allowEphemeralKey일 때만 임시 키를 만든다
     * @param keyId             JWKS의 kid. 키를 교체할 때 바꾼다
     * @param allowEphemeralKey 재시작하면 바뀌는 임시 키 허용 여부 (로컬·테스트 전용)
     */
    public record Jwt(String privateKey, String keyId, Duration accessTokenTtl, Duration refreshTokenTtl,
                      boolean allowEphemeralKey) {
    }
}
