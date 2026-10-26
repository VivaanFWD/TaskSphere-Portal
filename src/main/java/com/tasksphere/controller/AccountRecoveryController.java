package com.tasksphere.controller;

import com.tasksphere.service.AuthClientService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Controller
public class AccountRecoveryController {
    private final AuthClientService authClientService;

    public AccountRecoveryController(AuthClientService authClientService) {
        this.authClientService = authClientService;
    }

    @GetMapping("/account-recovery")
    public String recoveryPage() {
        return "account-recovery";
    }

    @PostMapping("/recovery/password/request")
    public String requestPasswordReset(
        @RequestParam("username") String username,
        RedirectAttributes redirectAttributes
    ) {
        try {
            authClientService.requestPasswordReset(username.trim());
            redirectAttributes.addFlashAttribute("passwordMessage",
                "If the account is registered, a verification code was sent to its email address.");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404 || ex.getStatusCode().value() == 400) {
                redirectAttributes.addFlashAttribute("passwordMessage",
                    "If the account is registered, a verification code was sent to its email address.");
            } else {
                redirectAttributes.addFlashAttribute("passwordError",
                    "The recovery service could not complete the request. Please try again later.");
            }
        } catch (ResourceAccessException ex) {
            redirectAttributes.addFlashAttribute("passwordError", "The recovery service is unavailable.");
        }
        return "redirect:/account-recovery";
    }

    @PostMapping("/recovery/password/reset")
    public String resetPassword(
        @RequestParam("username") String username,
        @RequestParam("otp") String otp,
        @RequestParam("newPassword") String newPassword,
        @RequestParam("confirmPassword") String confirmPassword,
        RedirectAttributes redirectAttributes
    ) {
        if (newPassword.length() < 12 || !newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("passwordError",
                "Use a password of at least 12 characters and enter it identically in both fields.");
            return "redirect:/account-recovery";
        }
        try {
            authClientService.resetPassword(username.trim(), otp.trim(), newPassword);
            redirectAttributes.addFlashAttribute("passwordMessage", "Password reset. Sign in with your new password.");
        } catch (RestClientResponseException ex) {
            redirectAttributes.addFlashAttribute("passwordError",
                "The code is invalid or expired, or the password could not be reset. Request a new code if needed.");
        } catch (ResourceAccessException ex) {
            redirectAttributes.addFlashAttribute("passwordError", "The recovery service is unavailable.");
        }
        return "redirect:/account-recovery";
    }

    @PostMapping("/recovery/user-id/request")
    public String requestUserIdRecovery(
        @RequestParam("email") String email,
        RedirectAttributes redirectAttributes
    ) {
        try {
            authClientService.requestUserIdRecovery(email.trim());
            redirectAttributes.addFlashAttribute("userIdMessage",
                "If the email is registered, a verification code was sent.");
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404 || ex.getStatusCode().value() == 400) {
                redirectAttributes.addFlashAttribute("userIdMessage",
                    "If the email is registered, a verification code was sent.");
            } else {
                redirectAttributes.addFlashAttribute("userIdError",
                    "The recovery service could not complete the request. Please try again later.");
            }
        } catch (ResourceAccessException ex) {
            redirectAttributes.addFlashAttribute("userIdError", "The recovery service is unavailable.");
        }
        return "redirect:/account-recovery";
    }

    @PostMapping("/recovery/user-id/verify")
    public String verifyUserId(
        @RequestParam("email") String email,
        @RequestParam("otp") String otp,
        RedirectAttributes redirectAttributes
    ) {
        try {
            String username = authClientService.verifyUserIdRecovery(email.trim(), otp.trim());
            redirectAttributes.addFlashAttribute("recoveredUsername", username);
        } catch (RestClientResponseException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("userIdError", "The code is invalid or expired. Request a new code if needed.");
        } catch (ResourceAccessException ex) {
            redirectAttributes.addFlashAttribute("userIdError", "The recovery service is unavailable.");
        }
        return "redirect:/account-recovery";
    }
}
