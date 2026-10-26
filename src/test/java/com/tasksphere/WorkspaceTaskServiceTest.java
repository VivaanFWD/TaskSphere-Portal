package com.tasksphere;

import com.tasksphere.domain.TaskCategory;
import com.tasksphere.domain.TaskPriority;
import com.tasksphere.domain.TaskStatus;
import com.tasksphere.service.WorkspaceTaskService;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "app.upload-dir=target/test-submission-uploads"
})
class WorkspaceTaskServiceTest {
    private static final String FACULTY = "workspace-test-faculty";
    private static final String STUDENT = "workspace-test-student";
    private static final String OTHER_STUDENT = "workspace-test-other";

    @Autowired
    private WorkspaceTaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
        jdbcTemplate.update("DELETE FROM workspace_tasks");
        jdbcTemplate.update("DELETE FROM academic_profiles WHERE username IN (?, ?, ?)",
            FACULTY, STUDENT, OTHER_STUDENT);
        insertProfile(FACULTY, "FACULTY");
        insertProfile(STUDENT, "STUDENT");
        insertProfile(OTHER_STUDENT, "STUDENT");
    }

    @Test
    void facultyAssignmentBelongsOnlyToSelectedStudentAndSubmissionLocksIt() {
        UUID studentId = profileId(STUDENT);
        taskService.assignTask(FACULTY, studentId, "Read chapter", "Prepare notes",
            TaskCategory.STUDY, TaskPriority.HIGH, LocalDate.now().plusDays(2));

        var assigned = taskService.studentTasks(STUDENT, true);
        assertEquals(1, assigned.size());
        assertEquals(FACULTY, assigned.get(0).getAssignedBy().getUsername());
        assertTrue(taskService.studentTasks(OTHER_STUDENT, true).isEmpty());

        Long taskId = assigned.get(0).getId();
        assertThrows(AccessDeniedException.class,
            () -> taskService.updateStatus(OTHER_STUDENT, taskId, TaskStatus.IN_PROGRESS));
        assertThrows(AccessDeniedException.class,
            () -> taskService.submitTask(OTHER_STUDENT, taskId, "not my task"));

        taskService.updateStatus(STUDENT, taskId, TaskStatus.IN_PROGRESS);
        taskService.submitTask(STUDENT, taskId, "Notes are ready");
        var submitted = taskService.studentTasks(STUDENT, true).get(0);
        assertEquals(TaskStatus.COMPLETED, submitted.getStatus());
        assertEquals("Notes are ready", submitted.getSubmissionNote());
        assertTrue(submitted.isSubmitted());
        assertThrows(IllegalStateException.class,
            () -> taskService.updateAssignedTask(FACULTY, taskId, "Changed", "", TaskCategory.STUDY,
                TaskPriority.LOW, null));
    }

    @Test
    void personalTasksArePrivateAndOnlyTheirOwnerCanChangeThem() {
        taskService.createPersonalTask(STUDENT, "My reading", null, TaskCategory.STUDY,
            TaskPriority.MEDIUM, null);

        var personal = taskService.studentTasks(STUDENT, false);
        assertEquals(1, personal.size());
        assertNull(personal.get(0).getAssignedBy());
        Long taskId = personal.get(0).getId();

        assertTrue(taskService.studentTasks(OTHER_STUDENT, false).isEmpty());
        assertThrows(AccessDeniedException.class,
            () -> taskService.updatePersonalTask(OTHER_STUDENT, taskId, "Hijack", null,
                TaskCategory.STUDY, TaskPriority.LOW, null, TaskStatus.COMPLETED));

        taskService.updatePersonalTask(STUDENT, taskId, "My reading list", "Week 2",
            TaskCategory.STUDY, TaskPriority.LOW, null, TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED, taskService.studentTasks(STUDENT, false).get(0).getStatus());
    }

    @Test
    void roleDashboardsRenderWithTheExpectedWorkspaceContent() throws Exception {
        taskService.createPersonalTask(STUDENT, "Render student task", null, TaskCategory.STUDY,
            TaskPriority.LOW, null);
        taskService.assignTask(FACULTY, profileId(STUDENT), "Render assigned task", null,
            TaskCategory.ASSIGNMENT, TaskPriority.HIGH, null);

        mockMvc.perform(get("/dashboard").with(user(STUDENT).roles("STUDENT")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("My Tasks")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Render student task")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Assigned by Faculty")));

        mockMvc.perform(get("/dashboard").with(user(FACULTY).roles("FACULTY")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Tasks You've Assigned")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Render assigned task")));

        mockMvc.perform(get("/task-analytics").with(user(STUDENT).roles("STUDENT")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Your Task Analytics")));

        mockMvc.perform(get("/task-analytics").with(user(FACULTY).roles("FACULTY")))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Student Task Analytics")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Progress by Student")));
    }

    private void insertProfile(String username, String role) {
        Timestamp now = Timestamp.from(java.time.Instant.now());
        jdbcTemplate.update("""
            INSERT INTO academic_profiles
                (id, auth_user_id, username, first_name, last_name, email, workspace_role,
                 account_status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """, profileId(username), UUID.randomUUID(), username, "Test", role,
            username + "@example.test", role, now, now);
    }

    private UUID profileId(String username) {
        return UUID.nameUUIDFromBytes(username.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
