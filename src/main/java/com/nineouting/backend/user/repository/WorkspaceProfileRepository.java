package com.nineouting.backend.user.repository;

import com.nineouting.backend.user.domain.WorkspaceProfile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceProfileRepository extends JpaRepository<WorkspaceProfile, Long> {

    Optional<WorkspaceProfile> findByUserIdAndWorkspaceId(Long userId, Long workspaceId);

    List<WorkspaceProfile> findByWorkspaceIdAndUserIdIn(Long workspaceId, Collection<Long> userIds);
}
