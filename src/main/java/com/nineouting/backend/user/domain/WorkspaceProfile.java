package com.nineouting.backend.user.domain;

import com.nineouting.backend.common.web.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 워크스페이스에서만 쓰는 이름·사진·소속 (REQ-114·115·118, 2026-10-28 결정: 계정 프로필 + 워크스페이스별 덮어쓰기).
 * null인 칸은 계정 프로필을 쓴다.
 */
@Getter
@Entity
@Table(name = "workspace_profiles")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkspaceProfile extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    private String name;

    private String affiliation;

    @Column(name = "profile_image_key")
    private String profileImageKey;

    public WorkspaceProfile(Long userId, Long workspaceId) {
        this.userId = userId;
        this.workspaceId = workspaceId;
    }

    /** 보낸 칸만 바꾼다. 빈 문자열이면 덮어쓰기를 지워 계정 프로필로 돌아간다 */
    public void update(String name, String affiliation) {
        if (name != null) {
            this.name = name.isEmpty() ? null : name;
        }
        if (affiliation != null) {
            this.affiliation = affiliation.isEmpty() ? null : affiliation;
        }
    }

    public void changeProfileImage(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }
}
