package com.nineouting.backend.user.dto;

/**
 * @param profileImageUrl 일정 시간만 유효한 주소. 사진이 없으면 null
 */
public record MyProfileResponse(Long userId, String email, String name, String affiliation, String profileImageUrl) {
}
