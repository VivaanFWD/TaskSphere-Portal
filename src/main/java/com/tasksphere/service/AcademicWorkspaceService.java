package com.tasksphere.service;

import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AcademicWorkspaceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AcademicWorkspaceService.class);
    private final JdbcTemplate jdbcTemplate;
    private final SubmissionAttachmentStorage attachmentStorage;
    private final java.time.ZoneId timezone;

    public AcademicWorkspaceService(
        JdbcTemplate jdbcTemplate,
        SubmissionAttachmentStorage attachmentStorage,
        @Value("${app.timezone:Asia/Kolkata}") String timezone
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.attachmentStorage = attachmentStorage;
        this.timezone = java.time.ZoneId.of(timezone);
    }

    @Transactional(readOnly = true)
    public List<CourseView> listCourses(String username, Set<String> roles) {
        if (roles.contains("ACADEMIC_OFFICE")) {
            return jdbcTemplate.query(courseSelect() + " ORDER BY c.term DESC, c.code",
                (rs, row) -> mapCourse(rs));
        }
        UUID userId = activeProfileId(username, roles.contains("FACULTY") ? "FACULTY" : "STUDENT");
        String scope = roles.contains("FACULTY")
            ? " WHERE c.faculty_user_id = ?"
            : " WHERE EXISTS (SELECT 1 FROM course_enrollments e WHERE e.course_id = c.id AND e.student_user_id = ?)";
        return jdbcTemplate.query(courseSelect() + scope + " ORDER BY c.term DESC, c.code",
            (rs, row) -> mapCourse(rs), userId);
    }

    @Transactional(readOnly = true)
    public List<FacultyProfile> searchFaculty(String search) {
        String query = search == null ? "" : search.trim();
        if (query.length() > 120) {
            throw new IllegalArgumentException("Faculty search must be no more than 120 characters.");
        }
        String pattern = "%" + query.toLowerCase(java.util.Locale.ROOT) + "%";
        return jdbcTemplate.query("""
            SELECT username, first_name, last_name, email, designation, department,
                   school, office_location, open_hours
            FROM academic_profiles
            WHERE workspace_role = 'FACULTY' AND account_status = 'ACTIVE'
              AND (LOWER(first_name || ' ' || last_name) LIKE ?
                   OR LOWER(username) LIKE ?
                   OR LOWER(department) LIKE ?
                   OR LOWER(school) LIKE ?)
            ORDER BY last_name, first_name
            """, (rs, row) -> mapFacultyProfile(rs), pattern, pattern, pattern, pattern);
    }

    @Transactional(readOnly = true)
    public FacultyProfile facultyProfile(String username) {
        List<FacultyProfile> profiles = jdbcTemplate.query("""
            SELECT username, first_name, last_name, email, designation, department,
                   school, office_location, open_hours
            FROM academic_profiles
            WHERE username = ? AND workspace_role = 'FACULTY' AND account_status = 'ACTIVE'
            """, (rs, row) -> mapFacultyProfile(rs), username);
        if (profiles.isEmpty()) {
            throw new IllegalArgumentException("Faculty profile not found.");
        }
        return profiles.get(0);
    }

    @Transactional
    public void updateFacultyProfile(
        String designation,
        String department,
        String school,
        String officeLocation,
        String openHours,
        String username,
        Set<String> roles
    ) {
        requireRole(roles, "FACULTY");
        activeProfileId(username, "FACULTY");
        jdbcTemplate.update("""
            UPDATE academic_profiles
            SET designation = ?, department = ?, school = ?, office_location = ?, open_hours = ?,
                updated_at = ?
            WHERE username = ? AND workspace_role = 'FACULTY'
            """, optional(designation, 160), optional(department, 160), optional(school, 160),
            optional(officeLocation, 120), optional(openHours, 2000),
            Timestamp.from(java.time.Instant.now()), username);
        audit(username, "FACULTY_PROFILE_UPDATED", "FACULTY_PROFILE", username, "Faculty directory profile updated");
    }

    @Transactional
    public CourseView createCourse(
        String code,
        String title,
        String description,
        String term,
        String facultyUsername,
        String actor
    ) {
        String normalizedCode = required(code, "Course code", 32).toUpperCase();
        validateCourseCode(normalizedCode);
        ensureCourseCodeAvailable(normalizedCode, null);
        String normalizedTitle = required(title, "Course title", 160);
        String normalizedDescription = optional(description, 4000);
        String normalizedTerm = required(term, "Term", 80);
        UUID facultyId = activeProfileId(facultyUsername, "FACULTY");
        UUID id = UUID.randomUUID();
        LocalDateTime now = now();
        jdbcTemplate.update("""
            INSERT INTO academic_courses
                (id, code, title, description, term, faculty_user_id, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, normalizedCode, normalizedTitle, normalizedDescription, normalizedTerm,
            facultyId, actor, Timestamp.valueOf(now));
        audit(actor, "COURSE_CREATED", "COURSE", id.toString(), normalizedCode);
        return courseById(id);
    }

    @Transactional
    public CourseView updateCourse(
        UUID courseId,
        String code,
        String title,
        String description,
        String term,
        String facultyUsername,
        String actor
    ) {
        courseById(courseId);
        String normalizedCode = required(code, "Course code", 32).toUpperCase();
        validateCourseCode(normalizedCode);
        ensureCourseCodeAvailable(normalizedCode, courseId);
        String normalizedTitle = required(title, "Course title", 160);
        String normalizedDescription = optional(description, 4000);
        String normalizedTerm = required(term, "Term", 80);
        UUID facultyId = activeProfileId(facultyUsername, "FACULTY");
        jdbcTemplate.update("""
            UPDATE academic_courses
            SET code = ?, title = ?, description = ?, term = ?, faculty_user_id = ?
            WHERE id = ?
            """, normalizedCode, normalizedTitle, normalizedDescription, normalizedTerm, facultyId, courseId);
        audit(actor, "COURSE_UPDATED", "COURSE", courseId.toString(), normalizedCode);
        return courseById(courseId);
    }

    @Transactional
    public void enrollStudent(UUID courseId, String studentUsername, String actor) {
        courseById(courseId);
        UUID studentId = activeProfileId(studentUsername, "STUDENT");
        int inserted = jdbcTemplate.update("""
            INSERT INTO course_enrollments (id, course_id, student_user_id, enrolled_by, enrolled_at)
            SELECT ?, ?, ?, ?, ?
            WHERE NOT EXISTS (
                SELECT 1 FROM course_enrollments WHERE course_id = ? AND student_user_id = ?
            )
            """, UUID.randomUUID(), courseId, studentId, actor, Timestamp.valueOf(now()), courseId, studentId);
        if (inserted != 1) {
            throw new IllegalArgumentException("This student is already enrolled in the course.");
        }
        audit(actor, "STUDENT_ENROLLED", "COURSE", courseId.toString(), studentUsername);
    }

    @Transactional
    public void removeStudent(UUID courseId, String studentUsername, String actor) {
        UUID studentId = profileId(studentUsername, "STUDENT");
        int removed = jdbcTemplate.update(
            "DELETE FROM course_enrollments WHERE course_id = ? AND student_user_id = ?",
            courseId, studentId);
        if (removed != 1) {
            throw new IllegalArgumentException("The student is not enrolled in this course.");
        }
        audit(actor, "STUDENT_UNENROLLED", "COURSE", courseId.toString(), studentUsername);
    }

    @Transactional(readOnly = true)
    public CoursePage coursePage(UUID courseId, String username, Set<String> roles) {
        CourseView course = courseById(courseId);
        UUID userId = roles.contains("ACADEMIC_OFFICE") ? null : requireCourseAccess(course, username, roles);
        List<AssignmentView> assignments = jdbcTemplate.query("""
            SELECT a.id, a.course_id, a.title, a.description, a.due_at, a.max_points,
                   a.question_paper_name,
                   s.id AS submission_id, s.body AS submission_body, s.submitted_at,
                   s.grade, s.feedback, s.attachment_name
            FROM course_assignments a
            LEFT JOIN assignment_submissions s
                ON s.assignment_id = a.id AND s.student_user_id = ?
            WHERE a.course_id = ?
            ORDER BY a.due_at
            """, (rs, row) -> mapAssignment(rs), userId, courseId);
        List<SubmissionView> submissions = isFacultyFor(course, username, roles)
            ? jdbcTemplate.query("""
                SELECT s.id, a.title AS assignment_title, p.username AS student_username,
                       p.first_name, p.last_name, s.body, s.submitted_at, s.grade,
                       s.feedback, s.attachment_name, a.max_points
                FROM assignment_submissions s
                JOIN course_assignments a ON a.id = s.assignment_id
                JOIN academic_profiles p ON p.auth_user_id = s.student_user_id
                WHERE a.course_id = ?
                ORDER BY s.submitted_at DESC
                """, (rs, row) -> mapSubmission(rs), courseId)
            : List.of();
        List<StudentView> enrolledStudents = roles.contains("ACADEMIC_OFFICE") || isFacultyFor(course, username, roles)
            ? jdbcTemplate.query("""
                SELECT p.username, p.first_name, p.last_name, p.email
                FROM course_enrollments e
                JOIN academic_profiles p ON p.auth_user_id = e.student_user_id
                WHERE e.course_id = ?
                ORDER BY p.last_name, p.first_name
                """, (rs, row) -> new StudentView(
                    rs.getString("username"), rs.getString("first_name"), rs.getString("last_name"),
                    rs.getString("email")), courseId)
            : List.of();
        List<MessageView> messages = jdbcTemplate.query("""
            SELECT m.id, m.author_username, p.first_name, p.last_name, m.body, m.created_at,
                   m.audience, m.recipient_username
            FROM course_messages m
            LEFT JOIN academic_profiles p ON p.username = m.author_username
            WHERE m.course_id = ?
              AND (m.audience = 'ALL' OR m.author_username = ? OR m.recipient_username = ?)
            ORDER BY m.created_at DESC
            LIMIT 50
            """, (rs, row) -> mapMessage(rs), courseId, username, username);
        return new CoursePage(course, assignments, submissions, enrolledStudents, messages,
            isFacultyFor(course, username, roles), roles.contains("STUDENT"),
            roles.contains("ACADEMIC_OFFICE"));
    }

    @Transactional
    public void createAssignment(
        UUID courseId,
        String title,
        String description,
        LocalDateTime dueAt,
        BigDecimal maxPoints,
        String username,
        Set<String> roles
    ) {
        try {
            createAssignment(courseId, title, description, dueAt, maxPoints, null, username, roles);
        } catch (IOException ex) {
            throw new IllegalStateException("The question paper could not be stored.", ex);
        }
    }

    @Transactional
    public void createAssignment(
        UUID courseId,
        String title,
        String description,
        LocalDateTime dueAt,
        BigDecimal maxPoints,
        MultipartFile questionPaper,
        String username,
        Set<String> roles
    ) throws IOException {
        CourseView course = courseById(courseId);
        requireFaculty(course, username, roles);
        String normalizedTitle = required(title, "Assignment title", 160);
        String normalizedDescription = required(description, "Assignment instructions", 4000);
        validateAssignmentDetails(dueAt, maxPoints);
        SubmissionAttachmentStorage.StoredAttachment stored = attachmentStorage.storePdf(questionPaper);
        if (stored != null) {
            deleteAttachmentAfterRollback(stored.storageKey());
        }
        UUID id = UUID.randomUUID();
        LocalDateTime currentTime = now();
        jdbcTemplate.update("""
            INSERT INTO course_assignments
                (id, course_id, title, description, due_at, max_points, created_by, created_at,
                 question_paper_name, question_paper_storage_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, courseId, normalizedTitle, normalizedDescription, Timestamp.valueOf(dueAt),
            maxPoints, username, Timestamp.valueOf(currentTime),
            stored == null ? null : stored.fileName(), stored == null ? null : stored.storageKey());
        audit(username, "ASSIGNMENT_CREATED", "ASSIGNMENT", id.toString(), normalizedTitle);
    }

    @Transactional
    public void updateAssignment(
        UUID assignmentId,
        String title,
        String description,
        LocalDateTime dueAt,
        BigDecimal maxPoints,
        MultipartFile questionPaper,
        String username,
        Set<String> roles
    ) throws IOException {
        AssignmentContext assignment = assignmentById(assignmentId);
        CourseView course = courseById(assignment.courseId());
        requireFaculty(course, username, roles);
        ensureAssignmentHasNoSubmissions(assignmentId);
        String normalizedTitle = required(title, "Assignment title", 160);
        String normalizedDescription = required(description, "Assignment instructions", 4000);
        validateAssignmentDetails(dueAt, maxPoints);
        SubmissionAttachmentStorage.StoredAttachment stored = attachmentStorage.storePdf(questionPaper);
        if (stored != null) {
            deleteAttachmentAfterRollback(stored.storageKey());
        }
        jdbcTemplate.update("""
            UPDATE course_assignments
            SET title = ?, description = ?, due_at = ?, max_points = ?,
                question_paper_name = ?, question_paper_storage_key = ?
            WHERE id = ?
            """, normalizedTitle, normalizedDescription, Timestamp.valueOf(dueAt), maxPoints,
            stored == null ? assignment.questionPaperName() : stored.fileName(),
            stored == null ? assignment.questionPaperStorageKey() : stored.storageKey(), assignmentId);
        if (stored != null && assignment.questionPaperStorageKey() != null) {
            deleteAttachmentAfterCommit(assignment.questionPaperStorageKey());
        }
        audit(username, "ASSIGNMENT_UPDATED", "ASSIGNMENT", assignmentId.toString(), normalizedTitle);
    }

    @Transactional
    public void deleteAssignment(UUID assignmentId, String username, Set<String> roles) {
        AssignmentContext assignment = assignmentById(assignmentId);
        CourseView course = courseById(assignment.courseId());
        requireFaculty(course, username, roles);
        ensureAssignmentHasNoSubmissions(assignmentId);
        jdbcTemplate.update("DELETE FROM course_assignments WHERE id = ?", assignmentId);
        if (assignment.questionPaperStorageKey() != null) {
            deleteAttachmentAfterCommit(assignment.questionPaperStorageKey());
        }
        audit(username, "ASSIGNMENT_DELETED", "ASSIGNMENT", assignmentId.toString(), assignment.title());
    }

    @Transactional(readOnly = true)
    public AttachmentDownload downloadQuestionPaper(UUID assignmentId, String username, Set<String> roles) {
        AssignmentContext assignment = assignmentById(assignmentId);
        CourseView course = courseById(assignment.courseId());
        requireCourseAccess(course, username, roles);
        if (assignment.questionPaperStorageKey() == null || assignment.questionPaperName() == null) {
            throw new IllegalArgumentException("This assignment has no question paper.");
        }
        Path file = attachmentStorage.resolve(assignment.questionPaperStorageKey());
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("The question paper is missing from storage.");
        }
        return new AttachmentDownload(assignment.questionPaperName(), file);
    }

    @Transactional
    public void submitAssignment(
        UUID assignmentId,
        String body,
        MultipartFile attachment,
        String username,
        Set<String> roles
    ) throws IOException {
        requireRole(roles, "STUDENT");
        AssignmentContext assignment = assignmentById(assignmentId);
        CourseView course = courseById(assignment.courseId());
        UUID studentId = requireCourseAccess(course, username, roles);
        if (!assignment.dueAt().isAfter(now())) {
            throw new IllegalArgumentException("The submission deadline has passed.");
        }
        String content = optional(body, 12000);
        ExistingSubmission existing = jdbcTemplate.query("""
            SELECT id, grade, attachment_storage_key FROM assignment_submissions
            WHERE assignment_id = ? AND student_user_id = ?
            """, rs -> rs.next() ? new ExistingSubmission(
                rs.getObject("id", UUID.class), rs.getBigDecimal("grade"),
                rs.getString("attachment_storage_key")) : null, assignmentId, studentId);
        if (existing != null && existing.grade() != null) {
            throw new IllegalStateException("A graded submission cannot be changed.");
        }
        SubmissionAttachmentStorage.StoredAttachment stored = attachmentStorage.store(attachment);
        if (stored != null) {
            deleteAttachmentAfterRollback(stored.storageKey());
        }
        if (content.length() < 10 && stored == null
            && (existing == null || existing.attachmentStorageKey() == null)) {
            throw new IllegalArgumentException("Provide at least 10 characters of work or attach a file.");
        }
        LocalDateTime now = now();
        if (existing == null) {
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO assignment_submissions
                    (id, assignment_id, student_user_id, body, submitted_at, attachment_name, attachment_storage_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, assignmentId, studentId, content, Timestamp.valueOf(now),
                stored == null ? null : stored.fileName(), stored == null ? null : stored.storageKey());
            audit(username, "ASSIGNMENT_SUBMITTED", "SUBMISSION", id.toString(), assignment.title());
        } else if (stored == null) {
            jdbcTemplate.update("""
                UPDATE assignment_submissions SET body = ?, submitted_at = ?
                WHERE id = ?
                """, content, Timestamp.valueOf(now), existing.id());
            audit(username, "SUBMISSION_UPDATED", "SUBMISSION", existing.id().toString(), assignment.title());
        } else {
            jdbcTemplate.update("""
                UPDATE assignment_submissions
                SET body = ?, submitted_at = ?, attachment_name = ?, attachment_storage_key = ?
                WHERE id = ?
                """, content, Timestamp.valueOf(now), stored.fileName(), stored.storageKey(), existing.id());
            audit(username, "SUBMISSION_UPDATED", "SUBMISSION", existing.id().toString(), assignment.title());
            if (existing.attachmentStorageKey() != null) {
                deleteAttachmentAfterCommit(existing.attachmentStorageKey());
            }
        }
    }

    @Transactional(readOnly = true)
    public AttachmentDownload downloadSubmissionAttachment(UUID submissionId, String username, Set<String> roles) {
        SubmissionContext submission = submissionById(submissionId);
        CourseView course = courseById(submission.courseId());
        UUID currentUserId = requireCourseAccess(course, username, roles);
        if (roles.contains("STUDENT") && !currentUserId.equals(submission.studentUserId())) {
            throw new AccessDeniedException("Students can only download their own submitted work.");
        }
        List<AttachmentDownload> files = jdbcTemplate.query("""
            SELECT attachment_name, attachment_storage_key
            FROM assignment_submissions WHERE id = ?
            """, (rs, row) -> {
                String key = rs.getString("attachment_storage_key");
                String name = rs.getString("attachment_name");
                if (key == null || name == null) {
                    return null;
                }
                Path file = attachmentStorage.resolve(key);
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("The submission attachment is missing from storage.");
                }
                return new AttachmentDownload(name, file);
            }, submissionId);
        return files.stream().filter(java.util.Objects::nonNull).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("This submission has no attachment."));
    }

    private void ensureAssignmentHasNoSubmissions(UUID assignmentId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM assignment_submissions WHERE assignment_id = ?", Integer.class, assignmentId);
        if (count != null && count > 0) {
            throw new IllegalStateException("Assignments cannot be edited or deleted after a student has submitted work.");
        }
    }

    private void validateAssignmentDetails(LocalDateTime dueAt, BigDecimal maxPoints) {
        if (dueAt == null || !dueAt.isAfter(now())) {
            throw new IllegalArgumentException("The due date must be in the future.");
        }
        if (maxPoints == null || maxPoints.signum() <= 0 || maxPoints.compareTo(new BigDecimal("100000")) > 0) {
            throw new IllegalArgumentException("Maximum points must be greater than 0 and no more than 100000.");
        }
    }

    @Transactional
    public void gradeSubmission(
        UUID submissionId,
        BigDecimal grade,
        String feedback,
        String username,
        Set<String> roles
    ) {
        requireRole(roles, "FACULTY");
        SubmissionContext submission = submissionById(submissionId);
        CourseView course = courseById(submission.courseId());
        requireFaculty(course, username, roles);
        if (grade == null || grade.signum() < 0 || grade.compareTo(submission.maxPoints()) > 0) {
            throw new IllegalArgumentException("Grade must be between 0 and the assignment's maximum points.");
        }
        String normalizedFeedback = optional(feedback, 4000);
        jdbcTemplate.update("""
            UPDATE assignment_submissions
            SET grade = ?, feedback = ?, graded_by = ?, graded_at = ?
            WHERE id = ?
            """, grade, normalizedFeedback, username, Timestamp.valueOf(now()), submissionId);
        audit(username, "SUBMISSION_GRADED", "SUBMISSION", submissionId.toString(), grade + "/" + submission.maxPoints());
    }

    @Transactional
    public void postMessage(
        UUID courseId,
        String body,
        String audience,
        String recipientUsername,
        String username,
        Set<String> roles
    ) {
        CourseView course = courseById(courseId);
        requireCourseAccess(course, username, roles);
        String content = required(body, "Message", 5000);
        String normalizedAudience = required(audience, "Message audience", 16).toUpperCase(java.util.Locale.ROOT);
        String normalizedRecipient = null;
        if ("INDIVIDUAL".equals(normalizedAudience)) {
            normalizedRecipient = required(recipientUsername, "Message recipient", 64);
            if (roles.contains("FACULTY")) {
                requireFaculty(course, username, roles);
                UUID studentId = activeProfileId(normalizedRecipient, "STUDENT");
                Integer enrolled = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM course_enrollments
                    WHERE course_id = ? AND student_user_id = ?
                    """, Integer.class, courseId, studentId);
                if (enrolled == null || enrolled == 0) {
                    throw new IllegalArgumentException("Choose a student enrolled in this course.");
                }
            } else if (roles.contains("STUDENT")) {
                if (!course.facultyUsername().equalsIgnoreCase(normalizedRecipient)) {
                    throw new IllegalArgumentException("Students can send individual messages only to the assigned faculty.");
                }
                activeProfileId(course.facultyUsername(), "FACULTY");
                normalizedRecipient = course.facultyUsername();
            } else {
                throw new IllegalArgumentException("Individual messages are available to course students and assigned faculty.");
            }
        } else if ("ALL".equals(normalizedAudience)) {
            if (StringUtils.hasText(recipientUsername)) {
                throw new IllegalArgumentException("Course-wide messages do not have an individual recipient.");
            }
        } else {
            throw new IllegalArgumentException("Choose an individual recipient or all course participants.");
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO course_messages
                (id, course_id, author_username, body, created_at, audience, recipient_username)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, courseId, username, content, Timestamp.valueOf(now()),
            normalizedAudience, normalizedRecipient);
        audit(username, "COURSE_MESSAGE_POSTED", "MESSAGE", id.toString(),
            course.code() + " " + normalizedAudience
                + (normalizedRecipient == null ? "" : " to " + normalizedRecipient));
    }

    @Transactional(readOnly = true)
    public ReportView report() {
        int students = count("SELECT COUNT(*) FROM academic_profiles WHERE workspace_role = 'STUDENT' AND account_status = 'ACTIVE'");
        int faculty = count("SELECT COUNT(*) FROM academic_profiles WHERE workspace_role = 'FACULTY' AND account_status = 'ACTIVE'");
        int courses = count("SELECT COUNT(*) FROM academic_courses");
        int enrollments = count("SELECT COUNT(*) FROM course_enrollments");
        int assignments = count("SELECT COUNT(*) FROM course_assignments");
        int pendingSubmissions = count("SELECT COUNT(*) FROM assignment_submissions WHERE grade IS NULL");
        List<CourseReport> courseReports = jdbcTemplate.query("""
            SELECT c.id, c.code, c.title, c.term, p.username AS faculty_username,
                   COUNT(DISTINCT e.id) AS enrollment_count,
                   COUNT(DISTINCT a.id) AS assignment_count,
                   COUNT(DISTINCT s.id) AS submission_count,
                   COUNT(DISTINCT CASE WHEN s.grade IS NULL THEN s.id END) AS pending_count
            FROM academic_courses c
            JOIN academic_profiles p ON p.auth_user_id = c.faculty_user_id
            LEFT JOIN course_enrollments e ON e.course_id = c.id
            LEFT JOIN course_assignments a ON a.course_id = c.id
            LEFT JOIN assignment_submissions s ON s.assignment_id = a.id
            GROUP BY c.id, c.code, c.title, c.term, p.username
            ORDER BY c.term DESC, c.code
            """, (rs, row) -> new CourseReport(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("title"),
                rs.getString("term"), rs.getString("faculty_username"),
                rs.getInt("enrollment_count"), rs.getInt("assignment_count"),
                rs.getInt("submission_count"), rs.getInt("pending_count")));
        List<AuditView> recentActivity = jdbcTemplate.query("""
            SELECT actor_username, action, entity_type, entity_id, details, created_at
            FROM academic_audit_events ORDER BY created_at DESC LIMIT 100
            """, (rs, row) -> new AuditView(
                rs.getString("actor_username"), rs.getString("action"), rs.getString("entity_type"),
                rs.getString("entity_id"), rs.getString("details"), localDateTime(rs, "created_at")));
        return new ReportView(students, faculty, courses, enrollments, assignments, pendingSubmissions,
            courseReports, recentActivity);
    }

    @Transactional
    public void recordAdministrativeAction(String actor, String action, String entityId, String details) {
        audit(actor, action, "ACCOUNT", entityId, details);
    }

    private String courseSelect() {
        return """
            SELECT c.id, c.code, c.title, c.description, c.term, fp.username AS faculty_username,
                   fp.first_name AS faculty_first_name, fp.last_name AS faculty_last_name,
                   (SELECT COUNT(*) FROM course_enrollments e WHERE e.course_id = c.id) AS enrollment_count
            FROM academic_courses c
            JOIN academic_profiles fp ON fp.auth_user_id = c.faculty_user_id
            """;
    }

    private CourseView courseById(UUID id) {
        List<CourseView> courses = jdbcTemplate.query(courseSelect() + " WHERE c.id = ?",
            (rs, row) -> mapCourse(rs), id);
        if (courses.isEmpty()) {
            throw new IllegalArgumentException("Course not found.");
        }
        return courses.get(0);
    }

    private UUID requireCourseAccess(CourseView course, String username, Set<String> roles) {
        if (roles.contains("ACADEMIC_OFFICE")) {
            return null;
        }
        if (roles.contains("FACULTY") && course.facultyUsername().equalsIgnoreCase(username)) {
            return activeProfileId(username, "FACULTY");
        }
        if (roles.contains("STUDENT")) {
            UUID studentId = activeProfileId(username, "STUDENT");
            Integer enrolled = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM course_enrollments WHERE course_id = ? AND student_user_id = ?
                """, Integer.class, course.id(), studentId);
            if (enrolled != null && enrolled > 0) {
                return studentId;
            }
        }
        throw new AccessDeniedException("You do not have access to this course.");
    }

    private void requireFaculty(CourseView course, String username, Set<String> roles) {
        requireRole(roles, "FACULTY");
        if (!course.facultyUsername().equalsIgnoreCase(username)) {
            throw new AccessDeniedException("Only the assigned faculty member can manage this course.");
        }
        activeProfileId(username, "FACULTY");
    }

    private boolean isFacultyFor(CourseView course, String username, Set<String> roles) {
        return roles.contains("FACULTY") && course.facultyUsername().equalsIgnoreCase(username);
    }

    private AssignmentContext assignmentById(UUID id) {
        return jdbcTemplate.query("""
            SELECT id, course_id, title, due_at, question_paper_name, question_paper_storage_key
            FROM course_assignments WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Assignment not found.");
                }
                return new AssignmentContext(rs.getObject("id", UUID.class),
                    rs.getObject("course_id", UUID.class), rs.getString("title"), localDateTime(rs, "due_at"),
                    rs.getString("question_paper_name"), rs.getString("question_paper_storage_key"));
            }, id);
    }

    private SubmissionContext submissionById(UUID id) {
        return jdbcTemplate.query("""
            SELECT s.id, a.course_id, a.max_points, s.student_user_id
            FROM assignment_submissions s
            JOIN course_assignments a ON a.id = s.assignment_id
            WHERE s.id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Submission not found.");
                }
                return new SubmissionContext(rs.getObject("id", UUID.class),
                    rs.getObject("course_id", UUID.class), rs.getBigDecimal("max_points"),
                    rs.getObject("student_user_id", UUID.class));
            }, id);
    }

    private void deleteAttachmentAfterCommit(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    attachmentStorage.delete(storageKey);
                } catch (IOException ex) {
                    LOGGER.error("Could not remove replaced submission attachment {}", storageKey, ex);
                }
            }
        });
    }

    private void deleteAttachmentAfterRollback(String storageKey) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    try {
                        attachmentStorage.delete(storageKey);
                    } catch (IOException ex) {
                        LOGGER.error("Could not remove rolled-back submission attachment {}", storageKey, ex);
                    }
                }
            }
        });
    }

    private UUID activeProfileId(String username, String role) {
        UUID id = jdbcTemplate.query("""
            SELECT auth_user_id FROM academic_profiles
            WHERE username = ? AND workspace_role = ? AND account_status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, username, role);
        if (id == null) {
            throw new AccessDeniedException("An active " + role.toLowerCase() + " profile is required.");
        }
        return id;
    }

    private UUID profileId(String username, String role) {
        UUID id = jdbcTemplate.query("""
            SELECT auth_user_id FROM academic_profiles WHERE username = ? AND workspace_role = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, username, role);
        if (id == null) {
            throw new AccessDeniedException("A " + role.toLowerCase() + " profile is required.");
        }
        return id;
    }

    private void requireRole(Set<String> roles, String required) {
        if (!roles.contains(required)) {
            throw new AccessDeniedException("This action requires the " + required.toLowerCase() + " role.");
        }
    }

    private void audit(String actor, String action, String entityType, String entityId, String details) {
        jdbcTemplate.update("""
            INSERT INTO academic_audit_events
                (id, actor_username, action, entity_type, entity_id, details, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), actor, action, entityType, entityId,
            optional(details, 2000), Timestamp.valueOf(now()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(timezone);
    }
    private int count(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private void validateCourseCode(String code) {
        if (!code.matches("[A-Z0-9][A-Z0-9_-]{1,31}")) {
            throw new IllegalArgumentException("Course code must use 2-32 letters, numbers, hyphens, or underscores.");
        }
    }

    private void ensureCourseCodeAvailable(String code, UUID courseId) {
        Integer matches = courseId == null
            ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM academic_courses WHERE code = ?", Integer.class, code)
            : jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM academic_courses WHERE code = ? AND id <> ?", Integer.class, code, courseId);
        if (matches != null && matches > 0) {
            throw new IllegalArgumentException("That course code is already in use.");
        }
    }

    private String required(String value, String label, int maximum) {
        if (!StringUtils.hasText(value) || value.trim().length() > maximum) {
            throw new IllegalArgumentException(label + " is required and must be no more than " + maximum + " characters.");
        }
        return value.trim();
    }

    private String optional(String value, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("Text must be no more than " + maximum + " characters.");
        }
        return normalized;
    }

    private CourseView mapCourse(ResultSet rs) throws SQLException {
        return new CourseView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("title"),
            rs.getString("description"), rs.getString("term"), rs.getString("faculty_username"),
            rs.getString("faculty_first_name") + " " + rs.getString("faculty_last_name"),
            rs.getInt("enrollment_count"));
    }

    private AssignmentView mapAssignment(ResultSet rs) throws SQLException {
        UUID submissionId = rs.getObject("submission_id", UUID.class);
        return new AssignmentView(rs.getObject("id", UUID.class), rs.getString("title"),
            rs.getString("description"), localDateTime(rs, "due_at"), rs.getBigDecimal("max_points"),
            submissionId == null ? "" : rs.getString("submission_body"),
            submissionId == null ? null : localDateTime(rs, "submitted_at"),
            submissionId == null ? null : rs.getBigDecimal("grade"),
            submissionId == null ? "" : rs.getString("feedback"), submissionId != null, submissionId,
            submissionId == null ? null : rs.getString("attachment_name"), rs.getString("question_paper_name"));
    }

    private FacultyProfile mapFacultyProfile(ResultSet rs) throws SQLException {
        return new FacultyProfile(rs.getString("username"), rs.getString("first_name"), rs.getString("last_name"),
            rs.getString("email"), rs.getString("designation"), rs.getString("department"),
            rs.getString("school"), rs.getString("office_location"), rs.getString("open_hours"));
    }

    private SubmissionView mapSubmission(ResultSet rs) throws SQLException {
        return new SubmissionView(rs.getObject("id", UUID.class), rs.getString("assignment_title"),
            rs.getString("student_username"), rs.getString("first_name") + " " + rs.getString("last_name"),
            rs.getString("body"), localDateTime(rs, "submitted_at"), rs.getBigDecimal("grade"),
            rs.getString("feedback"), rs.getBigDecimal("max_points"), rs.getString("attachment_name"));
    }

    private MessageView mapMessage(ResultSet rs) throws SQLException {
        String firstName = rs.getString("first_name");
        String author = firstName == null ? rs.getString("author_username")
            : firstName + " " + rs.getString("last_name");
        return new MessageView(rs.getObject("id", UUID.class), rs.getString("author_username"),
            author, rs.getString("body"), localDateTime(rs, "created_at"),
            rs.getString("audience"), rs.getString("recipient_username"));
    }

    private LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private record AssignmentContext(
        UUID id, UUID courseId, String title, LocalDateTime dueAt,
        String questionPaperName, String questionPaperStorageKey
    ) {}
    private record ExistingSubmission(UUID id, BigDecimal grade, String attachmentStorageKey) {}
    private record SubmissionContext(UUID id, UUID courseId, BigDecimal maxPoints, UUID studentUserId) {}

    public record CourseView(
        UUID id, String code, String title, String description, String term,
        String facultyUsername, String facultyName, int enrollmentCount
    ) {}

    public record AssignmentView(
        UUID id, String title, String description, LocalDateTime dueAt, BigDecimal maxPoints,
        String submissionBody, LocalDateTime submittedAt, BigDecimal grade, String feedback, boolean submitted,
        UUID submissionId, String attachmentName, String questionPaperName
    ) {
        public String dueAtInput() {
            return dueAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        }
    }

    public record FacultyProfile(
        String username, String firstName, String lastName, String email, String designation,
        String department, String school, String officeLocation, String openHours
    ) {}

    public record SubmissionView(
        UUID id, String assignmentTitle, String studentUsername, String studentName,
        String body, LocalDateTime submittedAt, BigDecimal grade, String feedback, BigDecimal maxPoints,
        String attachmentName
    ) {}

    public record AttachmentDownload(String fileName, Path path) {}

    public record StudentView(String username, String firstName, String lastName, String email) {}

    public record MessageView(
        UUID id, String authorUsername, String authorName, String body, LocalDateTime createdAt,
        String audience, String recipientUsername
    ) {}

    public record CoursePage(
        CourseView course, List<AssignmentView> assignments, List<SubmissionView> submissions,
        List<StudentView> enrolledStudents, List<MessageView> messages, boolean faculty,
        boolean student, boolean office
    ) {}

    public record CourseReport(
        UUID id, String code, String title, String term, String facultyUsername,
        int enrollmentCount, int assignmentCount, int submissionCount, int pendingCount
    ) {}

    public record AuditView(
        String actorUsername, String action, String entityType, String entityId,
        String details, LocalDateTime createdAt
    ) {}

    public record ReportView(
        int activeStudents, int activeFaculty, int courses, int enrollments,
        int assignments, int pendingSubmissions, List<CourseReport> courseReports,
        List<AuditView> recentActivity
    ) {}
}
