package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 보낸 항목만 바꾼다 (REQ-102 이름, REQ-105 소속). 소속을 빈 문자열로 보내면 지운다.
 */
public record UpdateProfileRequest(
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
        @Pattern(regexp = "^(?!\\s*$).+", message = "이름을 입력해 주세요")
        String name,

        @Size(max = 100, message = "소속은 100자 이하로 입력해 주세요")
        String affiliation
) {
    public UpdateProfileRequest {
        name = name == null ? null : name.trim();
        affiliation = affiliation == null ? null : affiliation.trim();
    }
}
