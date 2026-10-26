package com.tasksphere.service;

import com.tasksphere.domain.AcademicProfile;
import com.tasksphere.dto.ProvisionUserResponse;
import com.tasksphere.dto.UserIdentityResponse;
import com.tasksphere.repository.AcademicProfileRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

    @Transactional
    public AcademicProfile ensureProfile(UserIdentityResponse identity) {
        if (identity == null || identity.username() == null) {
            return null;
        }
        AcademicProfile profile = academicProfileRepository.findByUsername(identity.username())
            .orElseGet(() -> {
                AcademicProfile p = new AcademicProfile();
                p.setAuthUserId(UUID.randomUUID());
                return p;
            });
        profile.setUsername(identity.username());
        if (identity.firstName() != null && !identity.firstName().isBlank()) {
            profile.setFirstName(identity.firstName());
        } else if (profile.getFirstName() == null) {
            profile.setFirstName(identity.username());
        }
        if (identity.lastName() != null && !identity.lastName().isBlank()) {
            profile.setLastName(identity.lastName());
        } else if (profile.getLastName() == null) {
            profile.setLastName("");
        }
        if (identity.email() != null && !identity.email().isBlank()) {
            profile.setEmail(identity.email());
        } else if (profile.getEmail() == null) {
            profile.setEmail(identity.username() + "@tasksphere.edu");
        }
        if (identity.roles() != null && !identity.roles().isEmpty()) {
            if (identity.roles().contains("STUDENT")) {
                profile.setWorkspaceRole("STUDENT");
            } else if (identity.roles().contains("FACULTY")) {
                profile.setWorkspaceRole("FACULTY");
            } else if (identity.roles().contains("ACADEMIC_OFFICE")) {
                profile.setWorkspaceRole("ACADEMIC_OFFICE");
            }
        }
        profile.setAccountStatus(identity.status() != null ? identity.status() : "ACTIVE");
        profile.setUpdatedAt(Instant.now());
        return academicProfileRepository.save(profile);
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
