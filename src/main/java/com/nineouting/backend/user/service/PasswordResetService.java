package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.common.web.error.CommonErrorCode;
import com.nineouting.backend.user.domain.User;
import com.nineouting.backend.user.error.UserErrorCode;
import com.nineouting.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기 (REQ-009). 가입된 이메일이면 6자리 인증 번호(5분 유효)를 보낸다.
 * 5번 틀리면 무효가 되고 30분 동안 인증할 수 없다 (MSG-006).
 * 인증 번호가 맞으면 새 비밀번호를 설정할 수 있는 재설정 토큰(10분 유효, 한 번만 사용)을 준다.
 * 가입 여부를 알 수 없도록 인증 번호 요청 결과는 항상 같다.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    static final Duration LOCK_TTL = Duration.ofMinutes(30);
    static final int MAX_ATTEMPTS = 5;

    private static final String CODE_KEY = "password-reset-code:";
    private static final String TOKEN_KEY = "password-reset:";
    private static final String LOCK_KEY = "password-reset-lock:";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final String FIELD_USER_ID = "userId";
    private static final int CODE_BOUND = 1_000_000;
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginFailureStore loginFailureStore;
    private final MailNotifier mailNotifier;
    private final SecureRandom random = new SecureRandom();

    /**
     * 메일 발송 이벤트를 outbox에 기록하므로 트랜잭션 안에서 실행한다. 다시 요청하면 이전 인증 번호는 무효가 된다.
     * 제한 중에는 보내지 않지만 응답은 같다.
     */
    @Transactional
    public void request(String email) {
        if (isLocked(email)) {
            return;
        }
        userRepository.findByEmail(email).ifPresent(user -> {
            String code = String.format("%06d", random.nextInt(CODE_BOUND));
            String key = CODE_KEY + email;
            redis.delete(key);
            redis.opsForHash().putAll(key, Map.of(
                    FIELD_CODE, code, FIELD_ATTEMPTS, "0", FIELD_USER_ID, user.getId().toString()));
            redis.expire(key, CODE_TTL);
            mailNotifier.sendPasswordResetCode(email, code);
        });
    }

    /** 인증 번호를 확인하고 재설정 토큰을 돌려준다. 가입되지 않은 이메일은 만료와 같은 응답을 준다. */
    public String verify(String email, String code) {
        if (isLocked(email)) {
            throw new BusinessException(UserErrorCode.VERIFICATION_LOCKED);
        }
        String key = CODE_KEY + email;
        Map<Object, Object> saved = redis.opsForHash().entries(key);
        if (saved.isEmpty()) {
            throw new BusinessException(UserErrorCode.VERIFICATION_EXPIRED);
        }
        if (!saved.get(FIELD_CODE).equals(code)) {
            long attempts = redis.opsForHash().increment(key, FIELD_ATTEMPTS, 1);
            if (attempts >= MAX_ATTEMPTS) {
                redis.delete(key);
                redis.opsForValue().set(LOCK_KEY + email, "true", LOCK_TTL);
                throw new BusinessException(UserErrorCode.VERIFICATION_LOCKED);
            }
            throw new BusinessException(UserErrorCode.VERIFICATION_CODE_MISMATCH,
                    UserErrorCode.codeMismatchMessage(attempts, MAX_ATTEMPTS));
        }
        redis.delete(key);
        String token = newToken();
        redis.opsForValue().set(TOKEN_KEY + token, (String) saved.get(FIELD_USER_ID), TOKEN_TTL);
        return token;
    }

    /** 이전 비밀번호와 같으면 거절한다 (MSG-014). 이때는 토큰을 남겨 두어 다른 비밀번호로 다시 시도할 수 있다. */
    @Transactional
    public void confirm(String token, String newPassword) {
        String userId = redis.opsForValue().get(TOKEN_KEY + token);
        if (userId == null) {
            throw new BusinessException(UserErrorCode.INVALID_RESET_TOKEN);
        }
        User user = userRepository.findById(Long.valueOf(userId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.SAME_AS_OLD_PASSWORD);
        }
        // 같은 토큰으로 동시에 들어온 요청은 하나만 통과한다
        if (!Boolean.TRUE.equals(redis.delete(TOKEN_KEY + token))) {
            throw new BusinessException(UserErrorCode.INVALID_RESET_TOKEN);
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAll(user.getId());
        loginFailureStore.clear(user.getEmail());
    }

    private boolean isLocked(String email) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + email));
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
