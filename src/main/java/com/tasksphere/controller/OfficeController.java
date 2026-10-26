package com.tasksphere.controller;

import com.tasksphere.service.IdentityProvisioningService;
import com.tasksphere.service.AcademicWorkspaceService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@Validated
public class OfficeController {
    private final IdentityProvisioningService identityProvisioningService;
    private final AcademicWorkspaceService academicWorkspaceService;

    public OfficeController(
        IdentityProvisioningService identityProvisioningService,
        AcademicWorkspaceService academicWorkspaceService
    ) {
        this.identityProvisioningService = identityProvisioningService;
        this.academicWorkspaceService = academicWorkspaceService;
    }

    @GetMapping("/office/accounts")
    public String accounts(Model model) {
        model.addAttribute("created", false);
        model.addAttribute("failure", false);
        model.addAttribute("students", identityProvisioningService.listProfiles("STUDENT"));
        model.addAttribute("faculty", identityProvisioningService.listProfiles("FACULTY"));
        return "office-accounts";
    }

    @PostMapping("/office/accounts/{accountType}")
    public String createAccount(
        @PathVariable("accountType") String accountType,
        @ModelAttribute AccountForm form,
        RedirectAttributes redirectAttributes
    ) {
        String role = switch (accountType.toLowerCase()) {
            case "student" -> "STUDENT";
            case "faculty" -> "FACULTY";
            default -> throw new AccessDeniedException("Unsupported account type");
        };
        if (form.username() == null || !form.username().matches("[A-Za-z0-9._-]{3,64}")
            || form.firstName() == null || form.firstName().isBlank()
            || form.lastName() == null || form.lastName().isBlank()
            || form.email() == null || !form.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
            || form.initialPassword() == null || form.initialPassword().length() < 8) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "Enter a valid ID, name, email, and initial password of at least 8 characters.");
            return "redirect:/office/accounts";
        }
        try {
            IdentityProvisioningService.ProvisionResult created = identityProvisioningService.createAccount(
                form.username().trim(),
                form.firstName().trim(),
                form.lastName().trim(),
                form.email().trim(),
                form.initialPassword(),
                role
            );
            academicWorkspaceService.recordAdministrativeAction(
                currentUsername(), "ACCOUNT_PROVISIONED", created.authUserId(), created.username() + " " + role);
            redirectAttributes.addFlashAttribute("created", true);
            redirectAttributes.addFlashAttribute(
                "message",
                "Created " + created.username() + " (" + role + "). Ask them to change their initial password at first login."
            );
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The identity service rejected this account. Check for a duplicate ID or email.");
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The identity service is unavailable. No successful account creation was confirmed.");
        } catch (org.springframework.dao.DataAccessException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The identity was accepted but the local profile could not be recorded. Retry the same details to reconcile it.");
        } catch (IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/office/accounts";
    }

    @PostMapping("/office/accounts/{username}/status")
    public String updateStatus(
        @PathVariable("username") String username,
        @org.springframework.web.bind.annotation.RequestParam("status") String status,
        RedirectAttributes redirectAttributes
    ) {
        try {
            identityProvisioningService.updateAccountStatus(username, status);
            academicWorkspaceService.recordAdministrativeAction(
                currentUsername(), "ACCOUNT_STATUS_CHANGED", username, status);
            redirectAttributes.addFlashAttribute("message", "Updated " + username + " to " + status + ".");
        } catch (org.springframework.web.client.RestClientResponseException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The authentication service rejected the account status change.");
        } catch (org.springframework.web.client.ResourceAccessException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The authentication service is unavailable; account status was not confirmed.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/office/accounts";
    }

    public record AccountForm(String username, String firstName, String lastName, String email, String initialPassword) {}

    private String currentUsername() {
        return org.springframework.security.core.context.SecurityContextHolder.getContext()
            .getAuthentication().getName();
    }
}
