package com.tasksphere.repository;

import com.tasksphere.domain.AcademicProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AcademicProfileRepository extends JpaRepository<AcademicProfile, UUID> {
    Optional<AcademicProfile> findByAuthUserId(UUID authUserId);
    Optional<AcademicProfile> findByUsername(String username);
    List<AcademicProfile> findAllByWorkspaceRoleOrderByLastNameAscFirstNameAsc(String workspaceRole);
    List<AcademicProfile> findAllByWorkspaceRoleAndAccountStatusOrderByLastNameAscFirstNameAsc(
        String workspaceRole,
        String accountStatus
    );
}
