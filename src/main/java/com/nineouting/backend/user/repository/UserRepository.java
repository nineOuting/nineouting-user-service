package com.nineouting.backend.user.repository;

import com.nineouting.backend.user.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** REQ-207 탈퇴: 예전 워크스페이스별 프로필(V3, 지금은 쓰지 않는다)도 지운다 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "DELETE FROM workspace_profiles WHERE user_id = :userId", nativeQuery = true)
    void deleteWorkspaceProfiles(@org.springframework.data.repository.query.Param("userId") Long userId);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
