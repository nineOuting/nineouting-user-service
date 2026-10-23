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

    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.BAD_REQUEST, "이메일 인증을 먼저 완료해 주세요"),
    VERIFICATION_CODE_MISMATCH(HttpStatus.BAD_REQUEST, "인증 번호가 올바르지 않습니다. 다시 확인해 주세요."),
    VERIFICATION_EXPIRED(HttpStatus.BAD_REQUEST, "인증 시간이 지났습니다. 인증 번호를 다시 받아 주세요."),
    VERIFICATION_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "인증에 5회 실패하여 일시적으로 제한되었습니다. 30분 후에 다시 시도해 주세요."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다. 다시 확인해 주세요."),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS,
            "로그인에 5회 실패하여 일시적으로 제한되었습니다. 10분 후에 다시 시도하거나 비밀번호를 재설정해 주세요."),
    SAME_AS_OLD_PASSWORD(HttpStatus.BAD_REQUEST, "이전과 다른 비밀번호를 입력해 주세요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
    PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다"),
    INVALID_RESET_TOKEN(HttpStatus.BAD_REQUEST, "인증 시간이 지났습니다. 처음부터 다시 진행해 주세요"),
    UNSUPPORTED_IMAGE_TYPE(HttpStatus.BAD_REQUEST, "JPG, PNG, WEBP 사진만 올릴 수 있습니다"),
    PROFILE_IMAGE_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "프로필 사진 업로드가 완료되지 않았습니다"),
    PROFILE_IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "프로필 사진은 5MB 이하만 올릴 수 있습니다");

    private final HttpStatus status;
    private final String message;

    /** MSG-005 틀린 횟수를 붙인 인증 번호 불일치 문구. 예: "... (2/5)" */
    public static String codeMismatchMessage(long attempts, int maxAttempts) {
        return "%s (%d/%d)".formatted(VERIFICATION_CODE_MISMATCH.message, attempts, maxAttempts);
    }

    @Override
    public String getCode() {
        return name();
    }
}
