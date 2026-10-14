package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record PasswordResetRequest(
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 아닙니다")
        String email
) {
    public PasswordResetRequest {
        email = Emails.normalize(email);
    }
}
