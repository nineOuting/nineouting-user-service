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
 * 로그인 상태 유지를 껐으면 짧게 둔다 (값 끝에 :short). 갱신해도 처음 고른 쪽을 따른다.
 */
@Component
public class RefreshTokenStore {

    private static final String TOKEN_KEY = "refresh-token:";
    private static final String USER_TOKENS_KEY = "refresh-tokens:user:";
    private static final int TOKEN_BYTES = 32;
    private static final String SHORT_SUFFIX = ":short";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration shortTtl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenStore(StringRedisTemplate redis, UserServiceProperties properties) {
        this.redis = redis;
        this.ttl = properties.jwt().refreshTokenTtl();
        this.shortTtl = properties.jwt().shortRefreshTokenTtl();
    }

    public String issue(Long userId, boolean keepLoggedIn) {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Duration tokenTtl = ttl(keepLoggedIn);
        redis.opsForValue().set(TOKEN_KEY + token, userId + (keepLoggedIn ? "" : SHORT_SUFFIX), tokenTtl);
        redis.opsForSet().add(USER_TOKENS_KEY + userId, token);
        redis.expire(USER_TOKENS_KEY + userId, ttl);
        return token;
    }

    /** 토큰을 꺼내면서 지운다. 없거나 이미 쓴 토큰이면 빈 값. */
    public Optional<Owner> consume(String token) {
        String value = redis.opsForValue().getAndDelete(TOKEN_KEY + token);
        if (value == null) {
            return Optional.empty();
        }
        boolean keepLoggedIn = !value.endsWith(SHORT_SUFFIX);
        Long userId = Long.valueOf(keepLoggedIn ? value : value.substring(0, value.length() - SHORT_SUFFIX.length()));
        redis.opsForSet().remove(USER_TOKENS_KEY + userId, token);
        return Optional.of(new Owner(userId, keepLoggedIn));
    }

    /** refresh 토큰의 주인과 로그인 상태 유지 여부 */
    public record Owner(Long userId, boolean keepLoggedIn) {
    }

    /** 비밀번호를 바꾸면 모든 기기의 로그인을 끊는다. */
    public void revokeAll(Long userId) {
        Set<String> tokens = redis.opsForSet().members(USER_TOKENS_KEY + userId);
        if (tokens != null) {
            tokens.forEach(token -> redis.delete(TOKEN_KEY + token));
        }
        redis.delete(USER_TOKENS_KEY + userId);
    }

    /** 로그인 상태 유지를 켰으면 길게(14일), 껐으면 짧게(1시간) */
    public Duration ttl(boolean keepLoggedIn) {
        return keepLoggedIn ? ttl : shortTtl;
    }
}
