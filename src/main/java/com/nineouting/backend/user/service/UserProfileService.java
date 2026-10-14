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
 * 내 정보 조회·수정 (REQ-102~105)과 다른 서비스용 사용자 조회.
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

    /** REQ-104. 바꾸면 모든 기기의 로그인(refresh 토큰)을 끊는다. */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = find(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(UserErrorCode.PASSWORD_MISMATCH);
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenStore.revokeAll(userId);
    }

    /** REQ-103 ① 브라우저가 S3에 직접 올릴 주소를 발급한다. */
    public ProfileImageUploadUrlResponse createUploadUrl(Long userId, String contentType) {
        String extension = IMAGE_EXTENSIONS.get(contentType);
        if (extension == null) {
            throw new BusinessException(UserErrorCode.UNSUPPORTED_IMAGE_TYPE);
        }
        String key = keyPrefix(userId) + UUID.randomUUID() + "." + extension;
        return new ProfileImageUploadUrlResponse(profileImageStorage.uploadUrl(key, contentType), key);
    }

    /** REQ-103 ② 업로드가 끝난 사진을 내 프로필로 등록하고, 이전 사진은 지운다. */
    @Transactional
    public String registerProfileImage(Long userId, String imageKey) {
        if (!imageKey.startsWith(keyPrefix(userId))) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        long size = profileImageStorage.size(imageKey)
                .orElseThrow(() -> new BusinessException(UserErrorCode.PROFILE_IMAGE_NOT_UPLOADED));
        if (size > MAX_IMAGE_BYTES) {
            profileImageStorage.deleteQuietly(imageKey);
            throw new BusinessException(UserErrorCode.PROFILE_IMAGE_TOO_LARGE);
        }
        User user = find(userId);
        String previousKey = user.getProfileImageKey();
        user.changeProfileImage(imageKey);
        if (previousKey != null && !previousKey.equals(imageKey)) {
            profileImageStorage.deleteQuietly(previousKey);
        }
        return profileImageStorage.downloadUrl(imageKey);
    }

    /** REQ-103 ③ */
    @Transactional
    public void deleteProfileImage(Long userId) {
        User user = find(userId);
        if (user.getProfileImageKey() != null) {
            profileImageStorage.deleteQuietly(user.getProfileImageKey());
            user.changeProfileImage(null);
        }
    }

    /** 다른 서비스용 (회의 참석자·멤버 이름 표시). 없는 ID는 결과에서 빠진다. */
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> findSummaries(Collection<Long> ids) {
        if (ids.size() > MAX_LOOKUP_IDS) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "한 번에 " + MAX_LOOKUP_IDS + "명까지 조회할 수 있습니다");
        }
        return userRepository.findAllById(ids).stream()
                .map(user -> new UserSummaryResponse(user.getId(), user.getName(), user.getEmail(), imageUrl(user)))
                .toList();
    }

    private User find(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
    }

    private MyProfileResponse toMyProfile(User user) {
        return new MyProfileResponse(user.getId(), user.getEmail(), user.getName(), user.getAffiliation(),
                imageUrl(user));
    }

    private String imageUrl(User user) {
        return user.getProfileImageKey() == null ? null : profileImageStorage.downloadUrl(user.getProfileImageKey());
    }

    private static String keyPrefix(Long userId) {
        return "profile-images/" + userId + "/";
    }
}
