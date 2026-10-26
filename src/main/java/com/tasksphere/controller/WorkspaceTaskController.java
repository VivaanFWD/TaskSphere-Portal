package com.tasksphere.controller;

import com.tasksphere.domain.TaskCategory;
import com.tasksphere.domain.TaskPriority;
import com.tasksphere.domain.TaskStatus;
import com.tasksphere.service.WorkspaceTaskService;
import com.tasksphere.service.WorkspaceTaskAnalyticsService;
import com.tasksphere.dto.AuthTokenResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class WorkspaceTaskController {
    private final WorkspaceTaskService taskService;
    private final WorkspaceTaskAnalyticsService analyticsService;

    public WorkspaceTaskController(
        WorkspaceTaskService taskService,
        WorkspaceTaskAnalyticsService analyticsService
    ) {
        this.taskService = taskService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/dashboard")
    public String dashboard(
        org.springframework.security.core.Authentication authentication,
        HttpSession session,
        Model model
    ) {
        String username = authentication.getName();
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("user", displayName(session, username));
        model.addAttribute("workspaceRole", roles.contains("STUDENT") ? "STUDENT" : "FACULTY");
        model.addAttribute("categories", TaskCategory.values());
        model.addAttribute("priorities", TaskPriority.values());
        model.addAttribute("statuses", TaskStatus.values());
        if (roles.contains("STUDENT")) {
            model.addAttribute("summary", taskService.studentSummary(username));
            model.addAttribute("personalTasks", taskService.studentTasks(username, false));
            model.addAttribute("assignedTasks", taskService.studentTasks(username, true));
            return "student-dashboard";
        }
        if (roles.contains("FACULTY")) {
            model.addAttribute("students", taskService.activeStudents());
            var assignedTasks = taskService.facultyTasks(username);
            model.addAttribute("assignedTasks", assignedTasks);
            model.addAttribute("summary", Map.of(
                "total", (long) assignedTasks.size(),
                "pending", assignedTasks.stream().filter(task -> task.getStatus() == TaskStatus.PENDING).count(),
                "inProgress", assignedTasks.stream().filter(task -> task.getStatus() == TaskStatus.IN_PROGRESS).count(),
                "completed", assignedTasks.stream().filter(task -> task.getStatus() == TaskStatus.COMPLETED).count(),
                "overdue", assignedTasks.stream().filter(com.tasksphere.domain.WorkspaceTask::isOverdue).count()
            ));
            return "faculty-dashboard";
        }
        return "forward:/academic-dashboard";
    }

    @GetMapping("/task-analytics")
    public String taskAnalytics(
        org.springframework.security.core.Authentication authentication,
        HttpSession session,
        Model model
    ) {
        String username = authentication.getName();
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .collect(java.util.stream.Collectors.toSet());
        model.addAttribute("user", displayName(session, username));
        model.addAttribute("workspaceRole", roles.contains("STUDENT") ? "STUDENT" : "FACULTY");
        if (roles.contains("STUDENT")) {
            model.addAttribute("data", analyticsService.student(username));
            model.addAttribute("personalTasks", taskService.studentTasks(username, false));
            model.addAttribute("assignedTasks", taskService.studentTasks(username, true));
            model.addAttribute("studentView", true);
            return "task-analytics";
        }
        if (roles.contains("FACULTY")) {
            model.addAttribute("data", analyticsService.faculty(username));
            model.addAttribute("studentView", false);
            return "task-analytics";
        }
        throw new AccessDeniedException("Task analytics is available to students and faculty.");
    }

    @PostMapping("/tasks/add")
    public String addTask(
        org.springframework.security.core.Authentication authentication,
        @RequestParam String title,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) TaskCategory category,
        @RequestParam(required = false) TaskPriority priority,
        @RequestParam(required = false) String dueDate,
        RedirectAttributes redirect
    ) {
        try {
            taskService.createPersonalTask(authentication.getName(), title, description, category,
                priority, parseDate(dueDate));
            redirect.addFlashAttribute("success", "Task added to your list.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/tasks/assign")
    public String assignTask(
        org.springframework.security.core.Authentication authentication,
        @RequestParam String studentId,
        @RequestParam String title,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) TaskCategory category,
        @RequestParam(required = false) TaskPriority priority,
        @RequestParam(required = false) String dueDate,
        RedirectAttributes redirect
    ) {
        try {
            if ("ALL".equalsIgnoreCase(studentId.trim())) {
                int assignedCount = taskService.assignTaskToAll(authentication.getName(), title, description,
                    category, priority, parseDate(dueDate));
                redirect.addFlashAttribute("success", "Task assigned to the whole class (" + assignedCount + " students).");
            } else {
                UUID parsedStudentId = UUID.fromString(studentId.trim());
                taskService.assignTask(authentication.getName(), parsedStudentId, title, description,
                    category, priority, parseDate(dueDate));
                redirect.addFlashAttribute("success", "Task assigned to the selected student.");
            }
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/tasks/update/{id}")
    public String updatePersonalTask(
        org.springframework.security.core.Authentication authentication,
        @PathVariable Long id,
        @RequestParam String title,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) TaskCategory category,
        @RequestParam(required = false) TaskPriority priority,
        @RequestParam(required = false) String dueDate,
        @RequestParam TaskStatus status,
        RedirectAttributes redirect
    ) {
        try {
            taskService.updatePersonalTask(authentication.getName(), id, title, description,
                category, priority, parseDate(dueDate), status);
            redirect.addFlashAttribute("success", "Your task was updated.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/tasks/assigned/update/{id}")
    public String updateAssignedTask(
        org.springframework.security.core.Authentication authentication,
        @PathVariable Long id,
        @RequestParam String title,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) TaskCategory category,
        @RequestParam(required = false) TaskPriority priority,
        @RequestParam(required = false) String dueDate,
        RedirectAttributes redirect
    ) {
        try {
            taskService.updateAssignedTask(authentication.getName(), id, title, description,
                category, priority, parseDate(dueDate));
            redirect.addFlashAttribute("success", "Assigned task updated.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/tasks/status/{id}")
    public String updateStatus(
        org.springframework.security.core.Authentication authentication,
        @PathVariable Long id,
        @RequestParam TaskStatus status,
        RedirectAttributes redirect
    ) {
        try {
            taskService.updateStatus(authentication.getName(), id, status);
            redirect.addFlashAttribute("success", "Task status updated.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/tasks/submit/{id}")
    public String submitTask(
        org.springframework.security.core.Authentication authentication,
        @PathVariable Long id,
        @RequestParam(required = false) String submissionNote,
        RedirectAttributes redirect
    ) {
        try {
            taskService.submitTask(authentication.getName(), id, submissionNote);
            redirect.addFlashAttribute("success", "Task submitted successfully.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    @PostMapping("/tasks/delete/{id}")
    public String deleteTask(
        org.springframework.security.core.Authentication authentication,
        @PathVariable Long id,
        RedirectAttributes redirect
    ) {
        try {
            taskService.deleteTask(authentication.getName(), id);
            redirect.addFlashAttribute("success", "Task deleted.");
        } catch (Exception ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/dashboard";
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Enter a valid due date.");
        }
    }

    private String displayName(HttpSession session, String username) {
        Object currentUser = session.getAttribute("currentUser");
        if (currentUser instanceof AuthTokenResponse token
            && token.getDisplayName() != null
            && !token.getDisplayName().isBlank()) {
            return token.getDisplayName();
        }
        return username;
    }
}
