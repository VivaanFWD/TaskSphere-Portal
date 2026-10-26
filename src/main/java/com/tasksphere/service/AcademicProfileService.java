package com.tasksphere.service;

import com.tasksphere.domain.AcademicProfile;
import com.tasksphere.dto.ProvisionUserResponse;
import com.tasksphere.repository.AcademicProfileRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicProfileService {
    private final AcademicProfileRepository academicProfileRepository;

    public AcademicProfileService(AcademicProfileRepository academicProfileRepository) {
        this.academicProfileRepository = academicProfileRepository;
    }

    @Transactional
    public void linkIdentity(ProvisionUserResponse identity, String role) {
        AcademicProfile profile = academicProfileRepository.findByAuthUserId(identity.id())
            .orElseGet(AcademicProfile::new);
        profile.setAuthUserId(identity.id());
        profile.setUsername(identity.username());
        profile.setFirstName(identity.firstName());
        profile.setLastName(identity.lastName());
        profile.setEmail(identity.email());
        profile.setWorkspaceRole(role);
        profile.setAccountStatus(identity.status());
        profile.setUpdatedAt(Instant.now());
        academicProfileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public List<AcademicProfile> listProfiles(String role) {
        return academicProfileRepository.findAllByWorkspaceRoleOrderByLastNameAscFirstNameAsc(role);
    }

    @Transactional
    public void updateAccountStatus(String username, String status) {
        AcademicProfile profile = academicProfileRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("Academic profile not found."));
        profile.setAccountStatus(status);
        profile.setUpdatedAt(Instant.now());
    }
}
