package com.nineouting.backend.user.dto;

/**
 * 워크스페이스에서 보이는 내 프로필 (REQ-114·115·118). 값은 덮어쓴 값이 있으면 그것, 없으면 계정 프로필이다.
 *
 * @param customName         이 워크스페이스에서 이름을 따로 정했는지
 * @param customAffiliation  소속을 따로 정했는지
 * @param customProfileImage 사진을 따로 정했는지
 */
public record WorkspaceProfileResponse(Long workspaceId, String name, String affiliation, String profileImageUrl,
                                       boolean customName, boolean customAffiliation, boolean customProfileImage) {
}
