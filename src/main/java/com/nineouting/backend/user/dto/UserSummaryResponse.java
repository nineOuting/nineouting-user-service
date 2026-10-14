package com.nineouting.backend.user.dto;

/**
 * 다른 서비스가 참석자·멤버 이름을 표시할 때 쓰는 최소 정보 (REQ-013).
 */
public record UserSummaryResponse(Long userId, String name, String profileImageUrl) {
}
