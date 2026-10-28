package com.nineouting.backend.user.dto;

import jakarta.validation.constraints.Size;

/**
 * 보낸 칸만 바꾼다. 빈 문자열을 보내면 그 칸의 덮어쓰기를 지워 계정 프로필로 돌아간다.
 */
public record UpdateWorkspaceProfileRequest(
        @Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
        String name,

        @Size(max = 100, message = "소속은 100자 이하로 입력해 주세요")
        String affiliation
) {
    public UpdateWorkspaceProfileRequest {
        name = name == null ? null : name.trim();
        affiliation = affiliation == null ? null : affiliation.trim();
    }
}
