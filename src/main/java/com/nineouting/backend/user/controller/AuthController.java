package com.nineouting.backend.user.controller;

import com.nineouting.backend.common.web.response.ApiResponse;
import com.nineouting.backend.user.config.UserServiceProperties;
import com.nineouting.backend.user.dto.EmailVerificationConfirmRequest;
import com.nineouting.backend.user.dto.EmailVerificationRequest;
import com.nineouting.backend.user.dto.LoginRequest;
import com.nineouting.backend.user.dto.PasswordResetConfirmRequest;
import com.nineouting.backend.user.dto.PasswordResetRequest;
import com.nineouting.backend.user.dto.PasswordResetVerifyRequest;
import com.nineouting.backend.user.dto.PasswordResetVerifyResponse;
import com.nineouting.backend.user.dto.SignupRequest;
import com.nineouting.backend.user.dto.SignupResponse;
import com.nineouting.backend.user.dto.TokenResponse;
import com.nineouting.backend.user.service.AuthService;
import com.nineouting.backend.user.service.AuthService.IssuedTokens;
import com.nineouting.backend.user.service.EmailVerificationService;
import com.nineouting.backend.user.service.PasswordResetService;
import com.nineouting.backend.user.service.RefreshTokenStore;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_COOKIE_PATH = "/api/auth";

    private final EmailVerificationService emailVerificationService;
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final RefreshTokenStore refreshTokenStore;
    private final boolean refreshCookieSecure;

    public AuthController(EmailVerificationService emailVerificationService, AuthService authService,
                          PasswordResetService passwordResetService, RefreshTokenStore refreshTokenStore,
                          UserServiceProperties properties) {
        this.emailVerificationService = emailVerificationService;
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.refreshTokenStore = refreshTokenStore;
        this.refreshCookieSecure = properties.refreshCookieSecure();
    }

    /** REQ-011 인증 메일 발송 */
    @PostMapping("/email-verifications")
    public ApiResponse<Void> sendVerificationCode(@Valid @RequestBody EmailVerificationRequest request) {
        emailVerificationService.sendCode(request.email());
        return ApiResponse.ok();
    }

    /** REQ-011 인증 완료 확인 */
    @PostMapping("/email-verifications/confirm")
    public ApiResponse<Void> confirmVerificationCode(@Valid @RequestBody EmailVerificationConfirmRequest request) {
        emailVerificationService.confirm(request.email(), request.code());
        return ApiResponse.ok();
    }

    /** REQ-010~014 회원가입 */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        Long userId = authService.signup(request.email(), request.password(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new SignupResponse(userId)));
    }

    /** REQ-005~007 로그인 */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return withTokens(authService.login(request.email(), request.password()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return withTokens(authService.refresh(refreshToken));
    }

    /** REQ-004 로그아웃 */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", Duration.ZERO).toString())
                .body(ApiResponse.ok());
    }

    /** REQ-009 비밀번호 찾기 인증 번호 발송. 가입 여부와 상관없이 같은 응답을 준다. */
    @PostMapping("/password-reset")
    public ApiResponse<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request.email());
        return ApiResponse.ok();
    }

    /** REQ-009 인증 번호 확인. 맞으면 새 비밀번호 설정에 쓸 재설정 토큰을 준다. */
    @PostMapping("/password-reset/verify")
    public ApiResponse<PasswordResetVerifyResponse> verifyPasswordReset(
            @Valid @RequestBody PasswordResetVerifyRequest request) {
        return ApiResponse.ok(new PasswordResetVerifyResponse(
                passwordResetService.verify(request.email(), request.code())));
    }

    /** REQ-009 새 비밀번호 설정 */
    @PostMapping("/password-reset/confirm")
    public ApiResponse<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request.token(), request.newPassword());
        return ApiResponse.ok();
    }

    private ResponseEntity<ApiResponse<TokenResponse>> withTokens(IssuedTokens tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(tokens.refreshToken(), refreshTokenStore.ttl()).toString())
                .body(ApiResponse.ok(new TokenResponse(tokens.accessToken(), tokens.expiresIn())));
    }

    private ResponseCookie refreshCookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
