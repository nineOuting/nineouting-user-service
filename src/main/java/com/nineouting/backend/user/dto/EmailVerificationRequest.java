package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 아닙니다")
        @Size(max = 255, message = "이메일이 너무 깁니다")
        String email
) {
    public EmailVerificationRequest {
        email = Emails.normalize(email);
    }
}
