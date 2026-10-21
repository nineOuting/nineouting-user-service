package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.user.domain.User;
import com.nineouting.backend.user.error.UserErrorCode;
import com.nineouting.backend.user.repository.UserRepository;
import com.nineouting.backend.user.security.AccessTokenIssuer;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginFailureStore loginFailureStore;

    /** 없는 이메일로 로그인해도 비밀번호 비교 시간을 똑같이 써서, 응답 시간으로 가입 여부를 알 수 없게 한다. */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       EmailVerificationService emailVerificationService, AccessTokenIssuer accessTokenIssuer,
                       RefreshTokenStore refreshTokenStore, LoginFailureStore loginFailureStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenStore = refreshTokenStore;
        this.loginFailureStore = loginFailureStore;
        this.dummyPasswordHash = passwordEncoder.encode("dummy-password-0");
    }

    @Transactional
    public Long signup(String email, String password, String name) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
        emailVerificationService.consumeVerified(email);
        try {
            return userRepository.saveAndFlush(User.create(email, passwordEncoder.encode(password), name)).getId();
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일로 동시에 가입한 경우
            throw new BusinessException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    /** REQ-007. 5번 연속 실패하면 10분 동안 막는다 */
    @Transactional(readOnly = true)
    public IssuedTokens login(String email, String password) {
        loginFailureStore.requireNotLocked(email);
        Optional<User> user = userRepository.findByEmail(email);
        String hash = user.map(User::getPasswordHash).orElse(dummyPasswordHash);
        if (!passwordEncoder.matches(password, hash) || user.isEmpty()) {
            throw loginFailureStore.recordFailure(email);
        }
        loginFailureStore.clear(email);
        return issueTokens(user.get().getId());
    }

    public IssuedTokens refresh(String refreshToken) {
        Long userId = Optional.ofNullable(refreshToken)
                .flatMap(refreshTokenStore::consume)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVALID_REFRESH_TOKEN));
        return issueTokens(userId);
    }

    public void logout(String refreshToken) {
        if (refreshToken != null) {
            refreshTokenStore.consume(refreshToken);
        }
    }

    private IssuedTokens issueTokens(Long userId) {
        return new IssuedTokens(accessTokenIssuer.issue(userId), accessTokenIssuer.ttlSeconds(),
                refreshTokenStore.issue(userId));
    }

    public record IssuedTokens(String accessToken, long expiresIn, String refreshToken) {
    }
}
