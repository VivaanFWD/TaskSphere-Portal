package com.tasksphere.controller;

import com.tasksphere.dto.AuthTokenResponse;
import com.tasksphere.service.AuthClientService;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.client.RestClientException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class HomeController {
    private final AuthClientService authClientService;

    public HomeController(AuthClientService authClientService) {
        this.authClientService = authClientService;
    }

    @GetMapping("/")
    public String landingPage() {
        return "index";
    }

    @GetMapping("/login")
    public String loginPage(Model model, HttpSession session) {
        if (session.getAttribute("currentUser") != null) {
            return "redirect:/dashboard";
        }
        model.addAttribute("loginError", false);
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam("username") String username,
                          @RequestParam("password") String password,
                          HttpSession session,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        try {
            AuthTokenResponse tokenResponse = authClientService.login(username, password);
            if (tokenResponse.getRoles() == null || tokenResponse.getRoles().stream().noneMatch(role ->
                role.equals("STUDENT") || role.equals("FACULTY") || role.equals("ACADEMIC_OFFICE"))) {
                redirectAttributes.addFlashAttribute("errorMessage", "This account has no TaskSphere workspace role.");
                return "redirect:/login";
            }
            request.changeSessionId();
            session.setAttribute("currentUser", tokenResponse);
            session.setAttribute("token", tokenResponse.getTokenType() + " " + tokenResponse.getToken());
            if (tokenResponse.isPasswordChangeRequired()) {
                return "redirect:/password/change";
            }
            return "redirect:/dashboard";
        } catch (RestClientException ex) {
            redirectAttributes.addFlashAttribute("loginError", true);
            redirectAttributes.addFlashAttribute("errorMessage", "Sign-in failed. Check your credentials and confirm the authentication service is available.");
            return "redirect:/login";
        }
    }

    @GetMapping("/academic-dashboard")
    public String dashboard(HttpSession session, Model model) {
        AuthTokenResponse user = (AuthTokenResponse) session.getAttribute("currentUser");
        if (user == null) {
            return "redirect:/login";
        }
        if (user.getRoles() == null || !user.getRoles().contains("ACADEMIC_OFFICE")) {
            return "redirect:/access-denied";
        }
        model.addAttribute("user", user);
        model.addAttribute("roles", user.getRoles() == null ? java.util.Set.of() : user.getRoles());
        if (user.getRoles() != null && user.getRoles().stream().anyMatch(r -> r.equals("STUDENT"))) {
            return "student-dashboard";
        }
        if (user.getRoles() != null && user.getRoles().stream().anyMatch(r -> r.equals("FACULTY"))) {
            return "faculty-dashboard";
        }
        if (user.getRoles() != null && user.getRoles().stream().anyMatch(r -> r.equals("ACADEMIC_OFFICE"))) {
            return "office-dashboard";
        }
        return "redirect:/access-denied";
    }

    @GetMapping("/password/change")
    public String passwordChangePage(HttpSession session) {
        return session.getAttribute("token") == null ? "redirect:/login" : "password-change";
    }

    @PostMapping("/password/change")
    public String changePassword(
        @RequestParam("currentPassword") String currentPassword,
        @RequestParam("newPassword") String newPassword,
        @RequestParam("confirmPassword") String confirmPassword,
        HttpSession session,
        RedirectAttributes redirectAttributes
    ) {
        if (newPassword.length() < 12 || !newPassword.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Choose a password of at least 12 characters and make both entries match.");
            return "redirect:/password/change";
        }
        String token = (String) session.getAttribute("token");
        try {
            authClientService.changePassword(token, currentPassword, newPassword);
            session.invalidate();
            redirectAttributes.addFlashAttribute("successMessage", "Password changed. Sign in again with your new password.");
            return "redirect:/login";
        } catch (RestClientException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "The authentication service could not complete the password change.");
            return "redirect:/password/change";
        }
    }

    @GetMapping("/access-denied")
    public String accessDenied() {
        return "access-denied";
    }
}
