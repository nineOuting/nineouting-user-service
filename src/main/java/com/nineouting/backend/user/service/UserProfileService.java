package com.nineouting.backend.user.service;

import com.nineouting.backend.common.web.error.BusinessException;
import com.nineouting.backend.common.web.error.CommonErrorCode;
import com.nineouting.backend.user.domain.User;
import com.nineouting.backend.user.domain.WorkspaceProfile;
import com.nineouting.backend.user.dto.MyProfileResponse;
import com.nineouting.backend.user.dto.ProfileImageUploadUrlResponse;
import com.nineouting.backend.user.dto.UserSummaryResponse;
import com.nineouting.backend.user.dto.WorkspaceProfileResponse;
import com.nineouting.backend.user.error.UserErrorCode;
import com.nineouting.backend.user.repository.UserRepository;
import com.nineouting.backend.user.repository.WorkspaceProfileRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final WorkspaceProfileRepository workspaceProfileRepository;

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

    /** REQ-114·115·118 워크스페이스에서 보이는 내 프로필 */
    @Transactional(readOnly = true)
    public WorkspaceProfileResponse getWorkspaceProfile(Long userId, Long workspaceId) {
        return toWorkspaceProfile(find(userId), workspaceId,
                workspaceProfileRepository.findByUserIdAndWorkspaceId(userId, workspaceId).orElse(null));
    }

    /** 보낸 칸만 바꾼다. 빈 문자열이면 계정 프로필로 돌아간다 */
    @Transactional
    public WorkspaceProfileResponse updateWorkspaceProfile(Long userId, Long workspaceId, String name, String affiliation) {
        User user = find(userId);
        WorkspaceProfile profile = workspaceProfile(userId, workspaceId);
        profile.update(name, affiliation);
        return toWorkspaceProfile(user, workspaceId, profile);
    }

    /** 사진은 내 사진과 같은 업로드 주소(REQ-115 ①)로 올린 뒤 이 워크스페이스에 등록한다. 이전 워크스페이스 사진은 지운다 */
    @Transactional
    public WorkspaceProfileResponse registerWorkspaceProfileImage(Long userId, Long workspaceId, String imageKey) {
        requireUploadedImage(userId, imageKey);
        User user = find(userId);
        WorkspaceProfile profile = workspaceProfile(userId, workspaceId);
        String previousKey = profile.getProfileImageKey();
        profile.changeProfileImage(imageKey);
        if (previousKey != null && !previousKey.equals(imageKey) && !previousKey.equals(user.getProfileImageKey())) {
            profileImageStorage.deleteQuietly(previousKey);
        }
        return toWorkspaceProfile(user, workspaceId, profile);
    }

    /** 이 워크스페이스의 사진을 지워 계정 사진으로 돌아간다 */
    @Transactional
    public WorkspaceProfileResponse deleteWorkspaceProfileImage(Long userId, Long workspaceId) {
        User user = find(userId);
        WorkspaceProfile profile = workspaceProfileRepository.findByUserIdAndWorkspaceId(userId, workspaceId).orElse(null);
        if (profile != null && profile.getProfileImageKey() != null) {
            if (!profile.getProfileImageKey().equals(user.getProfileImageKey())) {
                profileImageStorage.deleteQuietly(profile.getProfileImageKey());
            }
            profile.changeProfileImage(null);
        }
        return toWorkspaceProfile(user, workspaceId, profile);
    }

    /**
     * 다른 서비스용 (회의 참석자·멤버 이름 표시). 없는 ID는 결과에서 빠진다.
     * workspaceId를 주면 그 워크스페이스에서 덮어쓴 이름·사진·소속을 쓴다 (없으면 계정 프로필).
     */
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> findSummaries(Collection<Long> ids, Long workspaceId) {
        if (ids.size() > MAX_LOOKUP_IDS) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "한 번에 " + MAX_LOOKUP_IDS + "명까지 조회할 수 있습니다");
        }
        Map<Long, WorkspaceProfile> overrides = workspaceId == null ? Map.of()
                : workspaceProfileRepository.findByWorkspaceIdAndUserIdIn(workspaceId, ids).stream()
                        .collect(Collectors.toMap(WorkspaceProfile::getUserId, Function.identity()));
        return userRepository.findAllById(ids).stream()
                .map(user -> {
                    WorkspaceProfile profile = overrides.get(user.getId());
                    return new UserSummaryResponse(user.getId(), name(user, profile), user.getEmail(),
                            imageUrl(user, profile), affiliation(user, profile));
                })
                .toList();
    }

    private WorkspaceProfile workspaceProfile(Long userId, Long workspaceId) {
        return workspaceProfileRepository.findByUserIdAndWorkspaceId(userId, workspaceId)
                .orElseGet(() -> workspaceProfileRepository.save(new WorkspaceProfile(userId, workspaceId)));
    }

    private WorkspaceProfileResponse toWorkspaceProfile(User user, Long workspaceId, WorkspaceProfile profile) {
        return new WorkspaceProfileResponse(workspaceId, name(user, profile), affiliation(user, profile),
                imageUrl(user, profile), profile != null && profile.getName() != null,
                profile != null && profile.getAffiliation() != null, profile != null && profile.getProfileImageKey() != null);
    }

    private static String name(User user, WorkspaceProfile profile) {
        return profile != null && profile.getName() != null ? profile.getName() : user.getName();
    }

    private static String affiliation(User user, WorkspaceProfile profile) {
        return profile != null && profile.getAffiliation() != null ? profile.getAffiliation() : user.getAffiliation();
    }

    private String imageUrl(User user, WorkspaceProfile profile) {
        return profile != null && profile.getProfileImageKey() != null
                ? profileImageStorage.downloadUrl(profile.getProfileImageKey()) : imageUrl(user);
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
                imageUrl(user));
    }

    private String imageUrl(User user) {
        return user.getProfileImageKey() == null ? null : profileImageStorage.downloadUrl(user.getProfileImageKey());
    }

    private static String keyPrefix(Long userId) {
        return "profile-images/" + userId + "/";
    }
}
