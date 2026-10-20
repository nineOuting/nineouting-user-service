package com.nineouting.backend.user.error;

import com.nineouting.backend.common.web.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * user-service 에러 코드 (docs/services/user-service.md 4.4).
 */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해 주세요"),
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다"),
    VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "인증 시간이 지났습니다. 다시 요청해 주세요"),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다"),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "인증 시간이 지났습니다. 처음부터 다시 진행해 주세요"),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "JPG, PNG, WEBP 사진만 올릴 수 있습니다"),
    PROFILE_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "프로필 사진 업로드가 완료되지 않았습니다"),
    PROFILE_IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "프로필 사진은 5MB 이하만 올릴 수 있습니다");

    private final HttpStatus status;
    private final String message;

    @Override
    public String getCode() {
        return name();
    }
}
