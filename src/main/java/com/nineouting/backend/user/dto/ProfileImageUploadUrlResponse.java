package com.nineouting.backend.user.dto;

/**
 * @param uploadUrl 브라우저가 사진을 PUT으로 직접 올릴 주소 (요청한 Content-Type 헤더를 그대로 붙여야 한다)
 * @param imageKey  업로드가 끝나면 PUT /api/users/me/profile-image 로 보내 등록한다
 */
public record ProfileImageUploadUrlResponse(String uploadUrl, String imageKey) {
}
