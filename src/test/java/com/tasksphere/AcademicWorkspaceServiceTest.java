package com.tasksphere;

import com.tasksphere.service.AcademicWorkspaceService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.upload-dir=target/test-submission-uploads"
})
class AcademicWorkspaceServiceTest {
    @Autowired
    private AcademicWorkspaceService academicWorkspaceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM academic_audit_events");
        jdbcTemplate.update("DELETE FROM course_messages");
        jdbcTemplate.update("DELETE FROM assignment_submissions");
        jdbcTemplate.update("DELETE FROM course_assignments");
        jdbcTemplate.update("DELETE FROM course_enrollments");
        jdbcTemplate.update("DELETE FROM academic_courses");
        jdbcTemplate.update("DELETE FROM academic_profiles");
        addProfile("faculty-1", "Faculty", "Test", "FACULTY");
        addProfile("student-1", "Student", "Test", "STUDENT");
        addProfile("office-1", "Office", "Test", "ACADEMIC_OFFICE");
    }

    @Test
    void courseEnrollmentAssignmentSubmissionGradingAndMessagesAreScopedAndAudited() throws Exception {
        var course = academicWorkspaceService.createCourse(
            "cs101", "Computer Science", "Foundations", "2026 Fall", "faculty-1", "office-1");
        assertEquals(0, academicWorkspaceService.listCourses("student-1", Set.of("STUDENT")).size());

        academicWorkspaceService.enrollStudent(course.id(), "student-1", "office-1");
        assertEquals(1, academicWorkspaceService.listCourses("student-1", Set.of("STUDENT")).size());
        assertEquals(1, academicWorkspaceService.listCourses("faculty-1", Set.of("FACULTY")).size());

        var dueAt = LocalDateTime.now().plusDays(2);
        academicWorkspaceService.createAssignment(course.id(), "Lab 1", "Build a small program",
            dueAt, new BigDecimal("20"), "faculty-1", Set.of("FACULTY"));
        var studentPage = academicWorkspaceService.coursePage(course.id(), "student-1", Set.of("STUDENT"));
        var assignment = studentPage.assignments().get(0);
        academicWorkspaceService.submitAssignment(assignment.id(),
            "This is a sufficiently detailed submission.", null, "student-1", Set.of("STUDENT"));
        var facultyPage = academicWorkspaceService.coursePage(course.id(), "faculty-1", Set.of("FACULTY"));
        var submission = facultyPage.submissions().get(0);

        assertThrows(IllegalArgumentException.class, () -> academicWorkspaceService.gradeSubmission(
            submission.id(), new BigDecimal("21"), "Too high", "faculty-1", Set.of("FACULTY")));
        academicWorkspaceService.gradeSubmission(submission.id(), new BigDecimal("18"),
            "Good work", "faculty-1", Set.of("FACULTY"));
        academicWorkspaceService.postMessage(course.id(), "Welcome to the course.", "ALL", null,
            "student-1", Set.of("STUDENT"));

        var refreshedPage = academicWorkspaceService.coursePage(course.id(), "student-1", Set.of("STUDENT"));
        assertEquals(new BigDecimal("18.00"), refreshedPage.assignments().get(0).grade());
        assertEquals("Good work", refreshedPage.assignments().get(0).feedback());
        assertEquals("Welcome to the course.", refreshedPage.messages().get(0).body());
        assertEquals(1, refreshedPage.course().enrollmentCount());
        assertEquals(0, academicWorkspaceService.report().pendingSubmissions());
        assertEquals(6, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM academic_audit_events", Integer.class));
    }

    @Test
    void studentCannotReadCourseWithoutEnrollment() {
        var course = academicWorkspaceService.createCourse(
            "math101", "Mathematics", "", "2026 Fall", "faculty-1", "office-1");
        assertThrows(AccessDeniedException.class, () ->
            academicWorkspaceService.coursePage(course.id(), "student-1", Set.of("STUDENT")));
    }

    @Test
    void academicOfficeCanEditCourseOfferingAndDuplicateCodesAreRejected() {
        var course = academicWorkspaceService.createCourse(
            "MATH101", "Mathematics", "Original", "2026 Fall", "faculty-1", "office-1");
        var updated = academicWorkspaceService.updateCourse(course.id(),
            "MATH102", "Applied Mathematics", "Updated description", "2027 Spring", "faculty-1", "office-1");
        assertEquals("MATH102", updated.code());
        assertEquals("Applied Mathematics", updated.title());
        assertThrows(IllegalArgumentException.class, () -> academicWorkspaceService.createCourse(
            "MATH102", "Duplicate", "", "2027 Spring", "faculty-1", "office-1"));
    }

    @Test
    void academicOfficeCanOpenCourseWithoutAnAcademicProfile() {
        var course = academicWorkspaceService.createCourse(
            "office101", "Office access", "", "2026 Fall", "faculty-1", "office-1");
        jdbcTemplate.update("DELETE FROM academic_profiles WHERE username = ?", "office-1");

        var page = academicWorkspaceService.coursePage(
            course.id(), "office-1", Set.of("ACADEMIC_OFFICE"));

        assertEquals(course.id(), page.course().id());
        assertEquals(true, page.office());
    }

    @Test
    void individualMessagesStayBetweenRecipientAndAuthorWhileAllMessagesReachCourse() {
        addProfile("student-2", "Another", "Student", "STUDENT");
        var course = academicWorkspaceService.createCourse(
            "msg101", "Messaging", "", "2026 Fall", "faculty-1", "office-1");
        academicWorkspaceService.enrollStudent(course.id(), "student-1", "office-1");
        academicWorkspaceService.enrollStudent(course.id(), "student-2", "office-1");

        academicWorkspaceService.postMessage(course.id(), "Question for faculty.",
            "INDIVIDUAL", "faculty-1", "student-1", Set.of("STUDENT"));
        assertEquals(1, academicWorkspaceService.coursePage(
            course.id(), "faculty-1", Set.of("FACULTY")).messages().size());
        assertEquals(1, academicWorkspaceService.coursePage(
            course.id(), "student-1", Set.of("STUDENT")).messages().size());
        assertEquals(0, academicWorkspaceService.coursePage(
            course.id(), "student-2", Set.of("STUDENT")).messages().size());

        academicWorkspaceService.postMessage(course.id(), "Private reply.",
            "INDIVIDUAL", "student-1", "faculty-1", Set.of("FACULTY"));
        assertEquals(2, academicWorkspaceService.coursePage(
            course.id(), "student-1", Set.of("STUDENT")).messages().size());
        assertEquals(0, academicWorkspaceService.coursePage(
            course.id(), "student-2", Set.of("STUDENT")).messages().size());
        assertThrows(IllegalArgumentException.class, () -> academicWorkspaceService.postMessage(
            course.id(), "Not for another student.", "INDIVIDUAL", "student-2",
            "student-1", Set.of("STUDENT")));

        academicWorkspaceService.postMessage(course.id(), "Announcement for all.",
            "ALL", null, "faculty-1", Set.of("FACULTY"));
        var secondStudentPage = academicWorkspaceService.coursePage(
            course.id(), "student-2", Set.of("STUDENT"));
        assertEquals(1, secondStudentPage.messages().size());
        assertTrue(academicWorkspaceService.coursePage(
            course.id(), "faculty-1", Set.of("FACULTY")).messages().stream()
                .anyMatch(message -> message.body().equals("Private reply.")
                    && message.recipientUsername().equals("student-1")));

        academicWorkspaceService.postMessage(course.id(), "A message for everyone.",
            "ALL", null, "student-1", Set.of("STUDENT"));
        assertEquals(2, academicWorkspaceService.coursePage(
            course.id(), "student-2", Set.of("STUDENT")).messages().size());
        assertTrue(academicWorkspaceService.coursePage(
            course.id(), "student-2", Set.of("STUDENT")).messages().stream()
                .anyMatch(message -> message.body().equals("A message for everyone.")
                    && message.authorUsername().equals("student-1")));
    }

    @Test
    void submissionAttachmentIsStoredAndOnlyVisibleToItsOwnerOrAssignedFaculty() throws Exception {
        addProfile("student-2", "Another", "Student", "STUDENT");
        var course = academicWorkspaceService.createCourse(
            "bio101", "Biology", "", "2026 Fall", "faculty-1", "office-1");
        academicWorkspaceService.enrollStudent(course.id(), "student-1", "office-1");
        academicWorkspaceService.enrollStudent(course.id(), "student-2", "office-1");
        academicWorkspaceService.createAssignment(course.id(), "Report", "Upload the report",
            LocalDateTime.now().plusDays(2), new BigDecimal("10"), "faculty-1", Set.of("FACULTY"));
        var assignment = academicWorkspaceService.coursePage(course.id(), "student-1", Set.of("STUDENT"))
            .assignments().get(0);
        var file = new MockMultipartFile("attachment", "field-report.pdf", "application/pdf",
            "%PDF-1.4\nsample attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        academicWorkspaceService.submitAssignment(assignment.id(), "", file, "student-1", Set.of("STUDENT"));
        var replacement = new MockMultipartFile("attachment", "revised-report.pdf", "application/pdf",
            "%PDF-1.4\nrevised attachment".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        academicWorkspaceService.submitAssignment(assignment.id(), "", replacement, "student-1", Set.of("STUDENT"));
        var submission = academicWorkspaceService.coursePage(course.id(), "faculty-1", Set.of("FACULTY"))
            .submissions().get(0);

        assertEquals("revised-report.pdf", submission.attachmentName());
        assertThrows(AccessDeniedException.class, () ->
            academicWorkspaceService.downloadSubmissionAttachment(submission.id(), "student-2", Set.of("STUDENT")));
        var download = academicWorkspaceService.downloadSubmissionAttachment(
            submission.id(), "faculty-1", Set.of("FACULTY"));
        try {
            assertTrue(Files.readString(download.path()).endsWith("revised attachment"));
        } finally {
            Files.deleteIfExists(download.path());
        }
    }

    @Test
    void facultyCanManageAssignmentsAndStudentsCanDownloadQuestionPapers() throws Exception {
        var course = academicWorkspaceService.createCourse(
            "assign101", "Assignment management", "", "Fall 2026", "faculty-1", "office-1");
        var questionPaper = new MockMultipartFile("questionPaper", "assessment.pdf", "application/pdf",
            "%PDF-1.4\nquestion paper".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var dueAt = LocalDateTime.now().plusDays(3);
        academicWorkspaceService.createAssignment(course.id(), "Assessment", "Complete the attached task",
            dueAt, new BigDecimal("25"), questionPaper, "faculty-1", Set.of("FACULTY"));
        var assignment = academicWorkspaceService.coursePage(course.id(), "faculty-1", Set.of("FACULTY"))
            .assignments().get(0);
        assertEquals("assessment.pdf", assignment.questionPaperName());
        assertThrows(AccessDeniedException.class, () ->
            academicWorkspaceService.updateAssignment(assignment.id(), "Changed", "Updated instructions",
                dueAt, new BigDecimal("25"), null, "student-1", Set.of("FACULTY")));

        var replacementPaper = new MockMultipartFile("questionPaper", "updated.pdf", "application/pdf",
            "%PDF-1.4\nupdated paper".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        academicWorkspaceService.updateAssignment(assignment.id(), "Updated assessment", "Updated instructions",
            dueAt, new BigDecimal("30"), replacementPaper, "faculty-1", Set.of("FACULTY"));
        assertEquals("Updated assessment", academicWorkspaceService.coursePage(
            course.id(), "faculty-1", Set.of("FACULTY")).assignments().get(0).title());

        academicWorkspaceService.enrollStudent(course.id(), "student-1", "office-1");
        var paper = academicWorkspaceService.downloadQuestionPaper(assignment.id(), "student-1", Set.of("STUDENT"));
        try {
            assertTrue(Files.readString(paper.path()).endsWith("updated paper"));
        } finally {
            Files.deleteIfExists(paper.path());
        }
        assertThrows(IllegalArgumentException.class, () -> {
            try {
                academicWorkspaceService.updateAssignment(assignment.id(), "Invalid PDF", "Instructions",
                    dueAt, new BigDecimal("30"),
                    new MockMultipartFile("questionPaper", "invalid.pdf", "application/pdf", "not a PDF".getBytes()),
                    "faculty-1", Set.of("FACULTY"));
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        });
        academicWorkspaceService.deleteAssignment(assignment.id(), "faculty-1", Set.of("FACULTY"));
        assertTrue(academicWorkspaceService.coursePage(
            course.id(), "student-1", Set.of("STUDENT")).assignments().isEmpty());
    }

    @Test
    void facultyCanEditDirectoryProfileAndUsersCanSearchIt() {
        academicWorkspaceService.updateFacultyProfile("Associate Professor", "Computer Science",
            "School of Computing", "SJT-111-A14", "Monday 10:00-11:00 AM\nFriday 9:30-10:30 AM",
            "faculty-1", Set.of("FACULTY"));

        var results = academicWorkspaceService.searchFaculty("computing");

        assertEquals(1, results.size());
        assertEquals("Associate Professor", results.get(0).designation());
        assertEquals("SJT-111-A14", results.get(0).officeLocation());
        assertTrue(results.get(0).openHours().contains("Monday"));
        assertThrows(AccessDeniedException.class, () -> academicWorkspaceService.updateFacultyProfile(
            "Prof", "Other", "Other school", "Room 1", "Friday", "student-1", Set.of("STUDENT")));
    }

    @Test
    void submittedWorkLocksAssignmentChanges() throws Exception {
        var course = academicWorkspaceService.createCourse(
            "locked101", "Locked assignment", "", "Fall 2026", "faculty-1", "office-1");
        academicWorkspaceService.enrollStudent(course.id(), "student-1", "office-1");
        academicWorkspaceService.createAssignment(course.id(), "Assignment", "Instructions",
            LocalDateTime.now().plusDays(1), new BigDecimal("10"), "faculty-1", Set.of("FACULTY"));
        var assignment = academicWorkspaceService.coursePage(course.id(), "student-1", Set.of("STUDENT"))
            .assignments().get(0);
        academicWorkspaceService.submitAssignment(assignment.id(), "A detailed written response.", null,
            "student-1", Set.of("STUDENT"));

        assertThrows(IllegalStateException.class, () -> academicWorkspaceService.deleteAssignment(
            assignment.id(), "faculty-1", Set.of("FACULTY")));
        assertThrows(IllegalStateException.class, () -> academicWorkspaceService.updateAssignment(
            assignment.id(), "Changed", "Instructions", LocalDateTime.now().plusDays(2),
            new BigDecimal("10"), null, "faculty-1", Set.of("FACULTY")));
    }

    private UUID addProfile(String username, String firstName, String lastName, String role) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbcTemplate.update("""
            INSERT INTO academic_profiles
                (id, auth_user_id, username, first_name, last_name, email, workspace_role,
                 account_status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """, UUID.randomUUID(), id, username, firstName, lastName,
            username + "@example.test", role, now, now);
        return id;
    }
}
