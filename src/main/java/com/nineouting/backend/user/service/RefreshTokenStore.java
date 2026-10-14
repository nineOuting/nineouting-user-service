package com.nineouting.backend.user.service;

import com.nineouting.backend.user.config.UserServiceProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * refresh 토큰은 의미 없는 랜덤 문자열이고, 값(사용자 ID)은 Redis에만 있다.
 * 한 번 쓰면 지우고 새로 발급하므로(rotation) 탈취된 토큰을 다시 쓰면 실패한다.
 */
@Component
public class RefreshTokenStore {

    private static final String TOKEN_KEY = "refresh-token:";
    private static final String USER_TOKENS_KEY = "refresh-tokens:user:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis, UserServiceProperties properties) {
        this.redis = redis;
        this.ttl = properties.jwt().refreshTokenTtl();
    }

    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        redis.opsForValue().set(TOKEN_KEY + token, userId.toString(), ttl);
        redis.opsForSet().add(USER_TOKENS_KEY + userId, token);
        redis.expire(USER_TOKENS_KEY + userId, ttl);
        return token;
    }

    /** 토큰을 꺼내면서 지운다. 없거나 이미 쓴 토큰이면 빈 값. */
    public Optional<Long> consume(String token) {
        String userId = redis.opsForValue().getAndDelete(TOKEN_KEY + token);
        if (userId == null) {
            return Optional.empty();
        }
        redis.opsForSet().remove(USER_TOKENS_KEY + userId, token);
        return Optional.of(Long.valueOf(userId));
    }

    /** 비밀번호를 바꾸면 모든 기기의 로그인을 끊는다. */
    public void revokeAll(Long userId) {
        Set<String> tokens = redis.opsForSet().members(USER_TOKENS_KEY + userId);
        if (tokens != null) {
            tokens.forEach(token -> redis.delete(TOKEN_KEY + token));
        }
        redis.delete(USER_TOKENS_KEY + userId);
    }

    public Duration ttl() {
        return ttl;
    }
}
