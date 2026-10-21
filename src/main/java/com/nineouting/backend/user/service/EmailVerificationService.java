package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.user.error.UserErrorCode;
import com.nineouting.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 전 이메일 인증 (REQ-011). 6자리 코드, 10분 유효, 5번 틀리면 무효가 되고 5분 동안 다시 받을 수 없다.
 * 인증을 마친 이메일은 30분 동안 가입할 수 있다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    static final Duration CODE_TTL = Duration.ofMinutes(10);
    static final Duration VERIFIED_TTL = Duration.ofMinutes(30);
    static final Duration LOCK_TTL = Duration.ofMinutes(5);
    static final int MAX_ATTEMPTS = 5;

    private static final String CODE_KEY = "email-verification:";
    private static final String VERIFIED_KEY = "email-verified:";
    private static final String LOCK_KEY = "email-verification-lock:";
    private static final String FIELD_CODE = "code";
    private static final String FIELD_ATTEMPTS = "attempts";
    private static final int CODE_BOUND = 1_000_000;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final MailNotifier mailNotifier;
    private final SecureRandom random = new SecureRandom();

    /** 메일 발송 이벤트를 outbox에 기록하므로 트랜잭션 안에서 실행한다. */
    @Transactional
    public void sendCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
        requireNotLocked(email);
        String code = String.format("%06d", random.nextInt(CODE_BOUND));
        String key = CODE_KEY + email;
        redis.delete(key);
        redis.opsForHash().putAll(key, Map.of(FIELD_CODE, code, FIELD_ATTEMPTS, "0"));
        redis.expire(key, CODE_TTL);
        mailNotifier.sendVerificationCode(email, code);
    }

    public void confirm(String email, String code) {
        requireNotLocked(email);
        String key = CODE_KEY + email;
        Object expected = redis.opsForHash().get(key, FIELD_CODE);
        if (expected == null) {
            throw new BusinessException(UserErrorCode.VERIFICATION_EXPIRED);
        }
        if (!expected.equals(code)) {
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
        redis.opsForValue().set(VERIFIED_KEY + email, "true", VERIFIED_TTL);
    }

    private void requireNotLocked(String email) {
        if (Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + email))) {
            throw new BusinessException(UserErrorCode.VERIFICATION_LOCKED);
        }
    }

    /** 가입할 때 인증 완료 표시를 확인하고 지운다. */
    public void consumeVerified(String email) {
        if (redis.opsForValue().getAndDelete(VERIFIED_KEY + email) == null) {
            throw new BusinessException(UserErrorCode.EMAIL_NOT_VERIFIED);
        }
    }
}
