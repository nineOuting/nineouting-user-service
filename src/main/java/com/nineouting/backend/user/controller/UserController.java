package com.nineouting.backend.user.controller;

import com.nineouting.backend.common.security.jwt.AuthenticatedUser;
import com.nineouting.backend.common.security.web.CurrentUser;
import com.nineouting.backend.common.web.response.ApiResponse;
import com.nineouting.backend.user.dto.ChangePasswordRequest;
import com.nineouting.backend.user.dto.MyProfileResponse;
import com.nineouting.backend.user.dto.ProfileImageResponse;
import com.nineouting.backend.user.dto.ProfileImageUploadUrlRequest;
import com.nineouting.backend.user.dto.ProfileImageUploadUrlResponse;
import com.nineouting.backend.user.dto.RegisterProfileImageRequest;
import com.nineouting.backend.user.dto.UpdateProfileRequest;
import com.nineouting.backend.user.dto.UpdateWorkspaceProfileRequest;
import com.nineouting.backend.user.dto.WorkspaceProfileResponse;
import com.nineouting.backend.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ApiResponse<MyProfileResponse> getMyProfile(@CurrentUser AuthenticatedUser user) {
        return ApiResponse.ok(userProfileService.getMyProfile(user.userId()));
    }

    /** REQ-114 이름, REQ-118 소속 */
    @PatchMapping
    public ApiResponse<MyProfileResponse> updateProfile(@CurrentUser AuthenticatedUser user,
                                                        @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(userProfileService.updateProfile(user.userId(), request.name(), request.affiliation()));
    }

    /** REQ-116 */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@CurrentUser AuthenticatedUser user,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changePassword(user.userId(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    /** REQ-115 ① S3 업로드 주소 발급 */
    @PostMapping("/profile-image/upload-url")
    public ApiResponse<ProfileImageUploadUrlResponse> createUploadUrl(
            @CurrentUser AuthenticatedUser user, @Valid @RequestBody ProfileImageUploadUrlRequest request) {
        return ApiResponse.ok(userProfileService.createUploadUrl(user.userId(), request.contentType()));
    }

    /** REQ-115 ② 업로드 완료 후 등록 */
    @PutMapping("/profile-image")
    public ApiResponse<ProfileImageResponse> registerProfileImage(
            @CurrentUser AuthenticatedUser user, @Valid @RequestBody RegisterProfileImageRequest request) {
        return ApiResponse.ok(new ProfileImageResponse(
                userProfileService.registerProfileImage(user.userId(), request.imageKey())));
    }

    /** REQ-115 ③ */
    @DeleteMapping("/profile-image")
    public ApiResponse<Void> deleteProfileImage(@CurrentUser AuthenticatedUser user) {
        userProfileService.deleteProfileImage(user.userId());
        return ApiResponse.ok();
    }

    /** REQ-114·115·118 워크스페이스에서 보이는 내 프로필 */
    @GetMapping("/workspaces/{workspaceId}/profile")
    public ApiResponse<WorkspaceProfileResponse> getWorkspaceProfile(@CurrentUser AuthenticatedUser user,
                                                                     @PathVariable Long workspaceId) {
        return ApiResponse.ok(userProfileService.getWorkspaceProfile(user.userId(), workspaceId));
    }

    /** 보낸 칸만 바꾼다. 빈 문자열이면 계정 프로필로 돌아간다 */
    @PatchMapping("/workspaces/{workspaceId}/profile")
    public ApiResponse<WorkspaceProfileResponse> updateWorkspaceProfile(@CurrentUser AuthenticatedUser user,
                                                                        @PathVariable Long workspaceId,
                                                                        @Valid @RequestBody UpdateWorkspaceProfileRequest request) {
        return ApiResponse.ok(userProfileService.updateWorkspaceProfile(user.userId(), workspaceId, request.name(),
                request.affiliation()));
    }

    /** 사진은 /profile-image/upload-url로 올린 뒤 등록한다 */
    @PutMapping("/workspaces/{workspaceId}/profile/image")
    public ApiResponse<WorkspaceProfileResponse> registerWorkspaceProfileImage(
            @CurrentUser AuthenticatedUser user, @PathVariable Long workspaceId,
            @Valid @RequestBody RegisterProfileImageRequest request) {
        return ApiResponse.ok(userProfileService.registerWorkspaceProfileImage(user.userId(), workspaceId, request.imageKey()));
    }

    /** 이 워크스페이스의 사진을 지워 계정 사진으로 돌아간다 */
    @DeleteMapping("/workspaces/{workspaceId}/profile/image")
    public ApiResponse<WorkspaceProfileResponse> deleteWorkspaceProfileImage(@CurrentUser AuthenticatedUser user,
                                                                             @PathVariable Long workspaceId) {
        return ApiResponse.ok(userProfileService.deleteWorkspaceProfileImage(user.userId(), workspaceId));
    }
}
