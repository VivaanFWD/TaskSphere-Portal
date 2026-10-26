package com.tasksphere.service;

import com.tasksphere.domain.AcademicProfile;
import com.tasksphere.domain.TaskCategory;
import com.tasksphere.domain.TaskPriority;
import com.tasksphere.domain.TaskStatus;
import com.tasksphere.domain.WorkspaceTask;
import com.tasksphere.repository.AcademicProfileRepository;
import com.tasksphere.repository.WorkspaceTaskRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceTaskService {
    private final WorkspaceTaskRepository taskRepository;
    private final AcademicProfileRepository profileRepository;

    public WorkspaceTaskService(
        WorkspaceTaskRepository taskRepository,
        AcademicProfileRepository profileRepository
    ) {
        this.taskRepository = taskRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public List<AcademicProfile> activeStudents() {
        return profileRepository.findAllByWorkspaceRoleAndAccountStatusOrderByLastNameAscFirstNameAsc(
            "STUDENT", "ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<WorkspaceTask> studentTasks(String username, boolean assigned) {
        AcademicProfile student = activeProfile(username, "STUDENT");
        return assigned
            ? taskRepository.findByStudent_IdAndAssignedByIsNotNullOrderByDueDateAscCreatedAtDesc(student.getId())
            : taskRepository.findByStudent_IdAndAssignedByIsNullOrderByDueDateAscCreatedAtDesc(student.getId());
    }

    @Transactional(readOnly = true)
    public List<WorkspaceTask> facultyTasks(String username) {
        AcademicProfile faculty = activeProfile(username, "FACULTY");
        return taskRepository.findByAssignedBy_IdOrderByDueDateAscCreatedAtDesc(faculty.getId());
    }

    @Transactional(readOnly = true)
    public Map<String, Long> studentSummary(String username) {
        List<WorkspaceTask> tasks = taskRepository.findByStudent_IdOrderByDueDateAscCreatedAtDesc(
            activeProfile(username, "STUDENT").getId());
        return Map.of(
            "total", (long) tasks.size(),
            "pending", tasks.stream().filter(task -> task.getStatus() == TaskStatus.PENDING).count(),
            "inProgress", tasks.stream().filter(task -> task.getStatus() == TaskStatus.IN_PROGRESS).count(),
            "completed", tasks.stream().filter(task -> task.getStatus() == TaskStatus.COMPLETED).count(),
            "overdue", tasks.stream().filter(WorkspaceTask::isOverdue).count()
        );
    }

    @Transactional
    public void createPersonalTask(
        String username, String title, String description, TaskCategory category,
        TaskPriority priority, LocalDate dueDate
    ) {
        AcademicProfile student = activeProfile(username, "STUDENT");
        saveNewTask(student, null, title, description, category, priority, dueDate);
    }

    @Transactional
    public void assignTask(
        String facultyUsername, UUID studentId, String title, String description,
        TaskCategory category, TaskPriority priority, LocalDate dueDate
    ) {
        AcademicProfile faculty = activeProfile(facultyUsername, "FACULTY");
        AcademicProfile student = profileRepository.findById(studentId)
            .filter(profile -> "STUDENT".equals(profile.getWorkspaceRole())
                && "ACTIVE".equals(profile.getAccountStatus()))
            .orElseThrow(() -> new IllegalArgumentException("Select an active student account."));
        saveNewTask(student, faculty, title, description, category, priority, dueDate);
    }

    @Transactional
    public int assignTaskToAll(
        String facultyUsername, String title, String description,
        TaskCategory category, TaskPriority priority, LocalDate dueDate
    ) {
        AcademicProfile faculty = activeProfile(facultyUsername, "FACULTY");
        List<AcademicProfile> students = activeStudents();
        if (students.isEmpty()) {
            throw new IllegalArgumentException("No active student accounts available to assign.");
        }
        for (AcademicProfile student : students) {
            saveNewTask(student, faculty, title, description, category, priority, dueDate);
        }
        return students.size();
    }

    @Transactional
    public void updatePersonalTask(
        String username, Long taskId, String title, String description,
        TaskCategory category, TaskPriority priority, LocalDate dueDate, TaskStatus status
    ) {
        AcademicProfile actor = activeProfile(username, "STUDENT");
        WorkspaceTask task = task(taskId);
        if (task.getAssignedBy() != null || !task.getStudent().getId().equals(actor.getId())) {
            throw new AccessDeniedException("You can only edit your own personal tasks.");
        }
        applyDetails(task, title, description, category, priority, dueDate);
        task.setStatus(status == null ? TaskStatus.PENDING : status);
        taskRepository.save(task);
    }

    @Transactional
    public void updateAssignedTask(
        String username, Long taskId, String title, String description,
        TaskCategory category, TaskPriority priority, LocalDate dueDate
    ) {
        AcademicProfile faculty = activeProfile(username, "FACULTY");
        WorkspaceTask task = task(taskId);
        if (task.getAssignedBy() == null || !task.getAssignedBy().getId().equals(faculty.getId())) {
            throw new AccessDeniedException("You can only edit tasks assigned by your account.");
        }
        if (task.isSubmitted()) {
            throw new IllegalStateException("Submitted tasks are locked and cannot be edited.");
        }
        applyDetails(task, title, description, category, priority, dueDate);
        taskRepository.save(task);
    }

    @Transactional
    public void updateStatus(String username, Long taskId, TaskStatus status) {
        WorkspaceTask task = task(taskId);
        AcademicProfile actor = activeProfile(username, task.getAssignedBy() == null ? "STUDENT" : roleFor(username));
        boolean personalOwner = task.getAssignedBy() == null && task.getStudent().getId().equals(actor.getId());
        boolean facultyOwner = task.getAssignedBy() != null && task.getAssignedBy().getId().equals(actor.getId());
        boolean assignedStudent = task.getAssignedBy() != null && task.getStudent().getId().equals(actor.getId());

        if (task.isSubmitted()) {
            throw new IllegalStateException("Submitted tasks are locked as completed.");
        }
        if (personalOwner || facultyOwner) {
            task.setStatus(status);
            taskRepository.save(task);
            return;
        }
        if (assignedStudent && status != TaskStatus.COMPLETED) {
            task.setStatus(status);
            taskRepository.save(task);
            return;
        }
        throw new AccessDeniedException(assignedStudent
            ? "Submit the task to mark it as completed."
            : "You don't have permission to update this task.");
    }

    @Transactional
    public void submitTask(String username, Long taskId, String submissionNote) {
        AcademicProfile student = activeProfile(username, "STUDENT");
        WorkspaceTask task = task(taskId);
        if (task.getAssignedBy() == null || !task.getStudent().getId().equals(student.getId())) {
            throw new AccessDeniedException("This assigned task is not assigned to your account.");
        }
        if (task.isSubmitted()) {
            throw new IllegalStateException("This task has already been submitted.");
        }
        task.setSubmissionNote(optionalText(submissionNote, 2000));
        task.setSubmittedAt(LocalDateTime.now());
        task.setStatus(TaskStatus.COMPLETED);
        taskRepository.save(task);
    }

    @Transactional
    public void deleteTask(String username, Long taskId) {
        WorkspaceTask task = task(taskId);
        AcademicProfile actor = activeProfile(username, task.getAssignedBy() == null ? "STUDENT" : roleFor(username));
        boolean personalOwner = task.getAssignedBy() == null && task.getStudent().getId().equals(actor.getId());
        boolean facultyOwner = task.getAssignedBy() != null && task.getAssignedBy().getId().equals(actor.getId());
        if (!personalOwner && !facultyOwner) {
            throw new AccessDeniedException("You don't have permission to delete this task.");
        }
        taskRepository.delete(task);
    }

    private void saveNewTask(
        AcademicProfile student, AcademicProfile faculty, String title, String description,
        TaskCategory category, TaskPriority priority, LocalDate dueDate
    ) {
        WorkspaceTask task = new WorkspaceTask();
        task.setStudent(student);
        task.setAssignedBy(faculty);
        applyDetails(task, title, description, category, priority, dueDate);
        task.setStatus(TaskStatus.PENDING);
        taskRepository.save(task);
    }

    private void applyDetails(
        WorkspaceTask task, String title, String description, TaskCategory category,
        TaskPriority priority, LocalDate dueDate
    ) {
        task.setTitle(requiredText(title, "Task title", 150));
        task.setDescription(optionalText(description, 1000));
        task.setCategory(category == null ? TaskCategory.ASSIGNMENT : category);
        task.setPriority(priority == null ? TaskPriority.MEDIUM : priority);
        task.setDueDate(dueDate);
    }

    private String requiredText(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(label + " is required and must be no more than " + max + " characters.");
        }
        return value.trim();
    }

    private String optionalText(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > max) {
            throw new IllegalArgumentException("Text must be no more than " + max + " characters.");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private AcademicProfile activeProfile(String username, String requiredRole) {
        AcademicProfile profile = profileRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("Your TaskSphere profile is not available."));
        if (!requiredRole.equals(profile.getWorkspaceRole()) || !"ACTIVE".equals(profile.getAccountStatus())) {
            throw new AccessDeniedException("Your account cannot perform this task action.");
        }
        return profile;
    }

    private String roleFor(String username) {
        return profileRepository.findByUsername(username)
            .map(AcademicProfile::getWorkspaceRole)
            .orElseThrow(() -> new IllegalArgumentException("Your TaskSphere profile is not available."));
    }

    private WorkspaceTask task(Long taskId) {
        return taskRepository.findWithProfilesById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found."));
    }
}
