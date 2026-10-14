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
import com.nineouting.backend.user.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    /** REQ-102 이름, REQ-105 소속 */
    @PatchMapping
    public ApiResponse<MyProfileResponse> updateProfile(@CurrentUser AuthenticatedUser user,
                                                        @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.ok(userProfileService.updateProfile(user.userId(), request.name(), request.affiliation()));
    }

    /** REQ-104 */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@CurrentUser AuthenticatedUser user,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        userProfileService.changePassword(user.userId(), request.currentPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    /** REQ-103 ① S3 업로드 주소 발급 */
    @PostMapping("/profile-image/upload-url")
    public ApiResponse<ProfileImageUploadUrlResponse> createUploadUrl(
            @CurrentUser AuthenticatedUser user, @Valid @RequestBody ProfileImageUploadUrlRequest request) {
        return ApiResponse.ok(userProfileService.createUploadUrl(user.userId(), request.contentType()));
    }

    /** REQ-103 ② 업로드 완료 후 등록 */
    @PutMapping("/profile-image")
    public ApiResponse<ProfileImageResponse> registerProfileImage(
            @CurrentUser AuthenticatedUser user, @Valid @RequestBody RegisterProfileImageRequest request) {
        return ApiResponse.ok(new ProfileImageResponse(
                userProfileService.registerProfileImage(user.userId(), request.imageKey())));
    }

    /** REQ-103 ③ */
    @DeleteMapping("/profile-image")
    public ApiResponse<Void> deleteProfileImage(@CurrentUser AuthenticatedUser user) {
        userProfileService.deleteProfileImage(user.userId());
        return ApiResponse.ok();
    }
}
