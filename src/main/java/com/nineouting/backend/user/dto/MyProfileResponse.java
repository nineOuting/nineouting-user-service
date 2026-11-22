package com.nineouting.backend.user.dto;

import java.time.LocalDateTime;

/**
 * @param profileImageUrl 일정 시간만 유효한 주소, 기본 캐릭터면 화면의 정적 파일 주소(/avatars/NN.png). 사진이 없으면 null
 * @param passwordChangedAt 비밀번호 마지막 변경 시각 (REQ-116)
 */
public record MyProfileResponse(Long userId, String email, String name, String affiliation, String profileImageUrl,
                                LocalDateTime passwordChangedAt) {
}
