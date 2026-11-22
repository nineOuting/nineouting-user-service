package com.nineouting.backend.user.dto;

import java.time.LocalDateTime;

/**
 * @param profileImageUrl   일정 시간만 유효한 주소. 사진이 없으면 null
 * @param passwordChangedAt 비밀번호를 마지막으로 정한 때 (설정 › 프로필 "마지막 변경")
 */
public record MyProfileResponse(Long userId, String email, String name, String affiliation, String profileImageUrl,
                                LocalDateTime passwordChangedAt) {
}
