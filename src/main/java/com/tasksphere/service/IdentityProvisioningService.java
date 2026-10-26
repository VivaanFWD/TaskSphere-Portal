package com.tasksphere.service;

import com.tasksphere.domain.AcademicProfile;
import com.tasksphere.service.AcademicProfileService;
import com.tasksphere.dto.ProvisionUserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityProvisioningService {
    private final AuthClientService authClientService;
    private final AcademicProfileService academicProfileService;

    public IdentityProvisioningService(
        AuthClientService authClientService,
        AcademicProfileService academicProfileService
    ) {
        this.authClientService = authClientService;
        this.academicProfileService = academicProfileService;
    }

    @Transactional
    public ProvisionResult createAccount(
        String username,
        String firstName,
        String lastName,
        String email,
        String initialPassword,
        String role
    ) {
        ProvisionUserResponse identity = authClientService.provisionUser(
            username, firstName, lastName, email, initialPassword, role
        );
        academicProfileService.linkIdentity(identity, role);
        return new ProvisionResult(identity.username(), role, identity.id().toString());
    }

    public java.util.List<AcademicProfile> listProfiles(String role) {
        return academicProfileService.listProfiles(role);
    }

    @Transactional
    public void updateAccountStatus(String username, String status) {
        ProvisionUserResponse current = authClientService.getUserByUsername(username);
        if (current.roles() == null || current.roles().stream().noneMatch(role ->
            role.equals("STUDENT") || role.equals("FACULTY"))) {
            throw new IllegalArgumentException("Only Student and Faculty accounts can be managed here.");
        }
        ProvisionUserResponse updated = authClientService.updateUserStatus(current.id(), status);
        String role = updated.roles().contains("STUDENT") ? "STUDENT" : "FACULTY";
        academicProfileService.linkIdentity(updated, role);
    }

    public record ProvisionResult(String username, String role, String authUserId) {}
}
