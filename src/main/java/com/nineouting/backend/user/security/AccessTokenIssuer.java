package com.nineouting.backend.user.security;

import com.nineouting.backend.common.security.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.PublicJwk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * RS256 access 토큰을 발급하고, 다른 서비스가 검증에 쓸 공개키 목록(JWKS)을 제공한다.
 * 토큰에는 사용자 ID만 넣는다 — 역할은 워크스페이스·회의마다 달라 각 서비스가 조회한다.
 */
public class AccessTokenIssuer {

    /** security-common이 기본으로 검사하는 발급자와 같아야 한다. */
    public static final String ISSUER = JwtProperties.DEFAULT_ISSUER;

    private final JwtKeys keys;
    private final Duration ttl;
    private final Clock clock;
    private final String jwksJson;

    public AccessTokenIssuer(JwtKeys keys, Duration ttl, Clock clock) {
        this.keys = keys;
        this.ttl = ttl;
        this.clock = clock;
        PublicJwk<?> jwk = Jwks.builder().key(keys.getPublicKey()).id(keys.getKeyId())
                .algorithm(Jwts.SIG.RS256.getId()).publicKeyUse("sig").build();
        this.jwksJson = "{\"keys\":[" + Jwks.json(jwk) + "]}";
    }

    public String issue(Long userId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .header().keyId(keys.getKeyId()).and()
                .issuer(ISSUER)
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(keys.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    public String jwksJson() {
        return jwksJson;
    }
}
