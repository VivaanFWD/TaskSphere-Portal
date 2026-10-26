package com.tasksphere.controller;

import com.tasksphere.service.AcademicWorkspaceService;
import com.tasksphere.service.IdentityProvisioningService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AcademicController {
    private final AcademicWorkspaceService academicWorkspaceService;
    private final IdentityProvisioningService identityProvisioningService;

    public AcademicController(
        AcademicWorkspaceService academicWorkspaceService,
        IdentityProvisioningService identityProvisioningService
    ) {
        this.academicWorkspaceService = academicWorkspaceService;
        this.identityProvisioningService = identityProvisioningService;
    }

    @GetMapping("/courses")
    public String courses(
        @RequestParam(value = "term", defaultValue = "") String term,
        Model model
    ) {
        Actor actor = actor();
        var availableCourses = academicWorkspaceService.listCourses(actor.username(), actor.roles());
        model.addAttribute("courses", term.isBlank() ? availableCourses : availableCourses.stream()
            .filter(course -> course.term().equals(term)).toList());
        model.addAttribute("terms", availableCourses.stream().map(AcademicWorkspaceService.CourseView::term)
            .distinct().sorted(java.util.Comparator.reverseOrder()).toList());
        model.addAttribute("selectedTerm", term);
        model.addAttribute("user", actor);
        return "courses";
    }

    @GetMapping("/faculty")
    public String facultyDirectory(
        @RequestParam(value = "search", defaultValue = "") String search,
        Model model
    ) {
        Actor actor = actor();
        model.addAttribute("faculty", academicWorkspaceService.searchFaculty(search));
        model.addAttribute("search", search);
        model.addAttribute("user", actor);
        return "faculty-directory";
    }

    @GetMapping("/faculty/profile")
    public String ownFacultyProfile() {
        Actor actor = actor();
        if (!actor.roles().contains("FACULTY")) {
            throw new org.springframework.security.access.AccessDeniedException("Faculty role required.");
        }
        return "redirect:/faculty/" + actor.username();
    }

    @GetMapping("/faculty/{username}")
    public String facultyDetails(@PathVariable("username") String username, Model model) {
        Actor actor = actor();
        var profile = academicWorkspaceService.facultyProfile(username);
        model.addAttribute("profile", profile);
        model.addAttribute("user", actor);
        model.addAttribute("editable", actor.roles().contains("FACULTY")
            && actor.username().equalsIgnoreCase(profile.username()));
        return "faculty-profile";
    }

    @PostMapping("/faculty/profile")
    public String updateFacultyProfile(
        @RequestParam(value = "designation", defaultValue = "") String designation,
        @RequestParam(value = "department", defaultValue = "") String department,
        @RequestParam(value = "school", defaultValue = "") String school,
        @RequestParam(value = "officeLocation", defaultValue = "") String officeLocation,
        @RequestParam(value = "openHours", defaultValue = "") String openHours,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.updateFacultyProfile(designation, department, school, officeLocation,
                openHours, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Your faculty directory profile was updated.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/faculty/" + actor.username();
    }

    @GetMapping("/courses/{courseId}")
    public String course(
        @PathVariable("courseId") UUID courseId,
        Model model
    ) {
        Actor actor = actor();
        model.addAttribute("page", academicWorkspaceService.coursePage(courseId, actor.username(), actor.roles()));
        model.addAttribute("user", actor);
        return "course";
    }

    @GetMapping("/office/courses")
    public String manageCourses(Model model) {
        model.addAttribute("courses", academicWorkspaceService.listCourses(
            actor().username(), actor().roles()));
        model.addAttribute("faculty", identityProvisioningService.listProfiles("FACULTY"));
        model.addAttribute("students", identityProvisioningService.listProfiles("STUDENT"));
        return "office-courses";
    }

    @PostMapping("/office/courses")
    public String createCourse(
        @RequestParam("code") String code,
        @RequestParam("title") String title,
        @RequestParam(value = "description", defaultValue = "") String description,
        @RequestParam("term") String term,
        @RequestParam("facultyUsername") String facultyUsername,
        RedirectAttributes redirectAttributes
    ) {
        try {
            var course = academicWorkspaceService.createCourse(
                code, title, description, term, facultyUsername, actor().username());
            redirectAttributes.addFlashAttribute("message", "Created course " + course.code() + ".");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/office/courses";
    }

    @PostMapping("/office/courses/{courseId}")
    public String updateCourse(
        @PathVariable("courseId") UUID courseId,
        @RequestParam("code") String code,
        @RequestParam("title") String title,
        @RequestParam(value = "description", defaultValue = "") String description,
        @RequestParam("term") String term,
        @RequestParam("facultyUsername") String facultyUsername,
        RedirectAttributes redirectAttributes
    ) {
        try {
            academicWorkspaceService.updateCourse(courseId, code, title, description, term,
                facultyUsername, actor().username());
            redirectAttributes.addFlashAttribute("message", "Course updated.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/office/courses";
    }

    @PostMapping("/office/courses/{courseId}/enroll")
    public String enroll(
        @PathVariable("courseId") UUID courseId,
        @RequestParam("studentUsername") String studentUsername,
        RedirectAttributes redirectAttributes
    ) {
        try {
            academicWorkspaceService.enrollStudent(courseId, studentUsername, actor().username());
            redirectAttributes.addFlashAttribute("message", "Enrolled " + studentUsername + ".");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/office/courses";
    }

    @PostMapping("/office/courses/{courseId}/unenroll")
    public String unenroll(
        @PathVariable("courseId") UUID courseId,
        @RequestParam("studentUsername") String studentUsername,
        RedirectAttributes redirectAttributes
    ) {
        try {
            academicWorkspaceService.removeStudent(courseId, studentUsername, actor().username());
            redirectAttributes.addFlashAttribute("message", "Removed " + studentUsername + " from the course.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/office/courses";
    }

    @PostMapping("/courses/{courseId}/assignments")
    public String createAssignment(
        @PathVariable("courseId") UUID courseId,
        @RequestParam("title") String title,
        @RequestParam("description") String description,
        @RequestParam("dueAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dueAt,
        @RequestParam("maxPoints") BigDecimal maxPoints,
        @RequestParam(value = "questionPaper", required = false) MultipartFile questionPaper,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.createAssignment(courseId, title, description, dueAt, maxPoints,
                questionPaper, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Assignment created.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        } catch (java.io.IOException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The question paper could not be stored.");
        }
        return "redirect:/courses/" + courseId;
    }

    @PostMapping("/assignments/{assignmentId}/edit")
    public String updateAssignment(
        @PathVariable("assignmentId") UUID assignmentId,
        @RequestParam("courseId") UUID courseId,
        @RequestParam("title") String title,
        @RequestParam("description") String description,
        @RequestParam("dueAt") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dueAt,
        @RequestParam("maxPoints") BigDecimal maxPoints,
        @RequestParam(value = "questionPaper", required = false) MultipartFile questionPaper,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.updateAssignment(assignmentId, title, description, dueAt, maxPoints,
                questionPaper, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Assignment updated.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        } catch (java.io.IOException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The question paper could not be stored.");
        }
        return "redirect:/courses/" + courseId;
    }

    @PostMapping("/assignments/{assignmentId}/delete")
    public String deleteAssignment(
        @PathVariable("assignmentId") UUID assignmentId,
        @RequestParam("courseId") UUID courseId,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.deleteAssignment(assignmentId, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Assignment deleted.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/courses/" + courseId;
    }

    @PostMapping("/assignments/{assignmentId}/submit")
    public String submit(
        @PathVariable("assignmentId") UUID assignmentId,
        @RequestParam("courseId") UUID courseId,
        @RequestParam(value = "body", defaultValue = "") String body,
        @RequestParam(value = "attachment", required = false) MultipartFile attachment,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.submitAssignment(
                assignmentId, body, attachment, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Submission saved.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        } catch (java.io.IOException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", "The attachment could not be stored. Retry or submit text only.");
        }
        return "redirect:/courses/" + courseId;
    }

    @GetMapping("/submissions/{submissionId}/attachment")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable("submissionId") UUID submissionId) {
        Actor actor = actor();
        var attachment = academicWorkspaceService.downloadSubmissionAttachment(
            submissionId, actor.username(), actor.roles());
        return attachmentResponse(attachment, MediaType.APPLICATION_OCTET_STREAM,
            ContentDisposition.attachment().filename(attachment.fileName(), StandardCharsets.UTF_8).build().toString());
    }

    @GetMapping("/submissions/{submissionId}/preview")
    public ResponseEntity<Resource> previewSubmission(@PathVariable("submissionId") UUID submissionId) {
        Actor actor = actor();
        var attachment = academicWorkspaceService.downloadSubmissionAttachment(
            submissionId, actor.username(), actor.roles());
        if (!attachment.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF submissions can be previewed in the browser.");
        }
        return attachmentResponse(attachment, MediaType.APPLICATION_PDF,
            ContentDisposition.inline().filename(attachment.fileName(), StandardCharsets.UTF_8).build().toString());
    }

    @GetMapping("/assignments/{assignmentId}/question-paper")
    public ResponseEntity<Resource> downloadQuestionPaper(@PathVariable("assignmentId") UUID assignmentId) {
        Actor actor = actor();
        var paper = academicWorkspaceService.downloadQuestionPaper(
            assignmentId, actor.username(), actor.roles());
        return attachmentResponse(paper, MediaType.APPLICATION_PDF,
            ContentDisposition.attachment().filename(paper.fileName(), StandardCharsets.UTF_8).build().toString());
    }

    @PostMapping("/submissions/{submissionId}/grade")
    public String grade(
        @PathVariable("submissionId") UUID submissionId,
        @RequestParam("courseId") UUID courseId,
        @RequestParam("grade") BigDecimal grade,
        @RequestParam(value = "feedback", defaultValue = "") String feedback,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.gradeSubmission(
                submissionId, grade, feedback, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Grade saved.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/courses/" + courseId;
    }

    @PostMapping("/courses/{courseId}/messages")
    public String postMessage(
        @PathVariable("courseId") UUID courseId,
        @RequestParam("body") String body,
        @RequestParam("audience") String audience,
        @RequestParam(value = "recipientUsername", required = false) String recipientUsername,
        RedirectAttributes redirectAttributes
    ) {
        Actor actor = actor();
        try {
            academicWorkspaceService.postMessage(
                courseId, body, audience, recipientUsername, actor.username(), actor.roles());
            redirectAttributes.addFlashAttribute("message", "Message posted.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("failure", true);
            redirectAttributes.addFlashAttribute("message", ex.getMessage());
        }
        return "redirect:/courses/" + courseId;
    }

    @GetMapping("/office/reports")
    public String reports(Model model) {
        model.addAttribute("report", academicWorkspaceService.report());
        return "office-reports";
    }

    private Actor actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> roles = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
            .collect(Collectors.toUnmodifiableSet());
        return new Actor(authentication.getName(), roles);
    }

    private ResponseEntity<Resource> attachmentResponse(
        AcademicWorkspaceService.AttachmentDownload attachment,
        MediaType mediaType,
        String disposition
    ) {
        return ResponseEntity.ok()
            .contentType(mediaType)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
            .header("X-Content-Type-Options", "nosniff")
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(new FileSystemResource(attachment.path()));
    }

    private record Actor(String username, Set<String> roles) {}
}
