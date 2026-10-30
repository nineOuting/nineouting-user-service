package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.user.error.UserErrorCode;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 로그인 실패 횟수 (REQ-007, MSG-010). 같은 이메일로 5번 연속 실패하면 10분 동안 로그인을 막는다.
 * 가입하지 않은 이메일도 똑같이 세어서, 제한 여부로 가입 여부를 알 수 없게 한다.
 */
@Component
@RequiredArgsConstructor
public class LoginFailureStore {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final String FAILURES_KEY = "login-failures:";
    private static final String LOCK_KEY = "login-lock:";

    private final StringRedisTemplate redis;

    public void requireNotLocked(String email) {
        if (Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + email))) {
            throw new BusinessException(UserErrorCode.LOGIN_LOCKED);
        }
    }

    /** 실패를 하나 세고 던질 예외를 돌려준다. 5번째면 제한을 걸고 LOGIN_LOCKED */
    public BusinessException recordFailure(String email) {
        String key = FAILURES_KEY + email;
        Long failures = redis.opsForValue().increment(key);
        redis.expire(key, LOCK_TTL);
        if (failures != null && failures >= MAX_FAILURES) {
            redis.delete(key);
            redis.opsForValue().set(LOCK_KEY + email, "true", LOCK_TTL);
            return new BusinessException(UserErrorCode.LOGIN_LOCKED);
        }
        return new BusinessException(UserErrorCode.LOGIN_FAILED);
    }

    /** 로그인에 성공하거나 비밀번호를 재설정하면 실패 횟수와 제한을 지운다 */
    public void clear(String email) {
        redis.delete(FAILURES_KEY + email);
        redis.delete(LOCK_KEY + email);
    }
}
