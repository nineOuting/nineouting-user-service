package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.common.web.error.CommonErrorCode;
import com.nineouting.backend.user.config.UserServiceProperties;
import com.nineouting.backend.user.domain.User;
import com.nineouting.backend.user.error.UserErrorCode;
import com.nineouting.backend.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기 (REQ-009). 가입된 이메일이면 30분짜리 재설정 링크를 보낸다.
 * 가입 여부를 알 수 없도록 요청 결과는 항상 같다.
 */
@Service
public class PasswordResetService {

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private static final String TOKEN_KEY = "password-reset:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final MailNotifier mailNotifier;
    private final String frontendBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(StringRedisTemplate redis, UserRepository userRepository,
                                PasswordEncoder passwordEncoder, RefreshTokenStore refreshTokenStore,
                                MailNotifier mailNotifier, UserServiceProperties properties) {
        this.redis = redis;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenStore = refreshTokenStore;
        this.mailNotifier = mailNotifier;
        this.frontendBaseUrl = properties.frontendBaseUrl();
    }

    public void request(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String token = newToken();
            redis.opsForValue().set(TOKEN_KEY + token, user.getId().toString(), TOKEN_TTL);
            mailNotifier.sendPasswordResetLink(email, frontendBaseUrl + "/password-reset?token=" + token);
        });
    }

    @Transactional
    public void confirm(String token, String newPassword) {
        String userId = redis.opsForValue().getAndDelete(TOKEN_KEY + token);
        if (userId == null) {
            throw new BusinessException(UserErrorCode.INVALID_RESET_TOKEN);
        }
        User user = userRepository.findById(Long.valueOf(userId))
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAll(user.getId());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
