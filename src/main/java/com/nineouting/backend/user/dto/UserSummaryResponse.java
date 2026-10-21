package com.nineouting.backend.user.dto;

/**
 * 다른 서비스가 참석자·멤버를 표시할 때 쓰는 최소 정보 (REQ-013, 워크스페이스 멤버 목록 REQ-033).
 * 내부 API 전용이라 이메일을 포함한다.
 */
public record UserSummaryResponse(Long userId, String name, String email, String profileImageUrl) {
}
