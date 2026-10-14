package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요")
        @Email(message = "이메일 형식이 아닙니다")
        @Size(max = 255, message = "이메일이 너무 깁니다")
        String email,

        @NotBlank(message = "비밀번호를 입력해 주세요")
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        String password,

        @NotBlank(message = "이름을 입력해 주세요")
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
        String name
) {
    public SignupRequest {
        email = Emails.normalize(email);
        name = name == null ? null : name.trim();
    }
}
