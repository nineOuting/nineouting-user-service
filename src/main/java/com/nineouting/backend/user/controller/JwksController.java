package com.nineouting.backend.user.controller;

import com.nineouting.backend.user.security.AccessTokenIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 다른 서비스(security-common)와 Istio가 access 토큰을 검증할 공개키 목록.
 * 표준 JWKS 형식이어야 하므로 ApiResponse로 감싸지 않는다.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private static final String JWKS_MEDIA_TYPE = "application/jwk-set+json";

    private final AccessTokenIssuer accessTokenIssuer;

    @GetMapping(value = "/.well-known/jwks.json", produces = {JWKS_MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public String jwks() {
        return accessTokenIssuer.jwksJson();
    }
}
