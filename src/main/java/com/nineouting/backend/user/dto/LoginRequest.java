package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 로그인은 비밀번호 규칙을 검사하지 않는다 (규칙이 바뀌어도 기존 사용자는 로그인할 수 있어야 한다). */
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 아닙니다")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요")
        String password
) {
    public LoginRequest {
        email = Emails.normalize(email);
    }
}
