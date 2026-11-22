package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.common.web.error.CommonErrorCode;
import com.nineouting.backend.user.domain.User;
import com.nineouting.backend.user.dto.MyProfileResponse;
import com.nineouting.backend.user.dto.ProfileImageUploadUrlResponse;
import com.nineouting.backend.user.dto.UserSummaryResponse;
import com.nineouting.backend.user.error.UserErrorCode;
import com.nineouting.backend.user.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 정보 조회·수정 (REQ-114~116·118, 117 레거시)과 다른 서비스용 사용자 조회.
 * 워크스페이스마다 이름·사진·소속을 덮어쓸 수 있다 (2026-10-28 기획서). 다른 서비스가 workspaceId를 주면 그 값으로 보여 준다.
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    static final int MAX_LOOKUP_IDS = 100;

    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenStore refreshTokenStore;
    private final ProfileImageStorage profileImageStorage;

    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile(Long userId) {
        return toMyProfile(find(userId));
    }

    @Transactional
    public MyProfileResponse updateProfile(Long userId, String name, String affiliation) {
        User user = find(userId);
        user.updateProfile(name, affiliation);
        return toMyProfile(user);
    }

    /** REQ-116. 바꾸면 모든 기기의 로그인(refresh 토큰)을 끊는다. */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = find(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.PASSWORD_MISMATCH);
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAll(userId);
    }

    /** REQ-115 ① 브라우저가 S3에 직접 올릴 주소를 발급한다. */
    public ProfileImageUploadUrlResponse createUploadUrl(Long userId, String contentType) {
        String extension = IMAGE_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException(UserErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        String key = keyPrefix(userId) + UUID.randomUUID() + "." + extension;
        return new ProfileImageUploadUrlResponse(profileImageStorage.uploadUrl(key, contentType), key);
    }

    /** REQ-115 ② 업로드가 끝난 사진을 내 프로필로 등록하고, 이전 사진은 지운다. */
    @Transactional
    public String registerProfileImage(Long userId, String imageKey) {
        requireUploadedImage(userId, imageKey);
        User user = find(userId);
        String previousKey = user.getProfileImageKey();
        user.changeProfileImage(imageKey);
        if (previousKey != null && !previousKey.equals(imageKey)) {
            profileImageStorage.deleteQuietly(previousKey);
        }
        return profileImageStorage.downloadUrl(imageKey);
    }

    /** REQ-115 ③ */
    @Transactional
    public void deleteProfileImage(Long userId) {
        User user = find(userId);
        if (user.getProfileImageKey() != null) {
            profileImageStorage.deleteQuietly(user.getProfileImageKey());
            user.changeProfileImage(null);
        }
    }

    /**
     * REQ-207 회원 탈퇴. 프로필 사진, 로그인 유지 토큰, 계정을 지운다 (되돌릴 수 없다).
     * 워크스페이스에서 나가기와 회의록 삭제는 화면이 먼저 각 서비스에 요청한다 (소유한 것이 있으면 거기서 막힌다)
     */
    @Transactional
    public void withdraw(Long userId) {
        User user = find(userId);
        if (user.getProfileImageKey() != null) {
            profileImageStorage.deleteQuietly(user.getProfileImageKey());
        }
        refreshTokenStore.revokeAll(userId);
        userRepository.deleteWorkspaceProfiles(userId);
        userRepository.delete(user);
    }

    /**
     * 다른 서비스용 (회의 참석자·멤버 이름 표시). 없는 ID는 결과에서 빠진다.
     * 프로필은 모든 워크스페이스에 공통이라 workspaceId는 받기만 하고 쓰지 않는다 (REQ-114, 2026-11-18 시트)
     */
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> findSummaries(Collection<Long> ids, Long workspaceId) {
        if (ids.size() > MAX_LOOKUP_IDS) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "한 번에 " + MAX_LOOKUP_IDS + "명까지 조회할 수 있습니다");
        }
        return userRepository.findAllById(ids).stream()
                .map(user -> new UserSummaryResponse(user.getId(), user.getName(), user.getEmail(), imageUrl(user),
                        user.getAffiliation()))
                .toList();
    }

    /** 내 업로드 경로의 사진이고, 실제로 올라가 있고, 크기 제한 안인지 */
    private void requireUploadedImage(Long userId, String imageKey) {
        if (!imageKey.startsWith(keyPrefix(userId))) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        long size = profileImageStorage.size(imageKey)
                .orElseThrow(() -> new BusinessException(UserErrorCode.PROFILE_IMAGE_NOT_UPLOADED));
        if (size > MAX_IMAGE_BYTES) {
            profileImageStorage.deleteQuietly(imageKey);
            throw new BusinessException(UserErrorCode.PROFILE_IMAGE_TOO_LARGE);
        }
    }

    private User find(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    private MyProfileResponse toMyProfile(User user) {
        return new MyProfileResponse(user.getId(), user.getEmail(), user.getName(), user.getAffiliation(),
                imageUrl(user), user.getPasswordChangedAt());
    }

    private String imageUrl(User user) {
        return user.getProfileImageKey() == null ? null : profileImageStorage.downloadUrl(user.getProfileImageKey());
    }

    private static String keyPrefix(Long userId) {
        return "profile-images/" + userId + "/";
    }
}
