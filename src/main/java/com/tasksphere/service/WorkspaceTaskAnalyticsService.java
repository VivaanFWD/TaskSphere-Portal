package com.tasksphere.service;

import com.tasksphere.domain.TaskCategory;
import com.tasksphere.domain.TaskPriority;
import com.tasksphere.domain.TaskStatus;
import com.tasksphere.domain.WorkspaceTask;
import com.tasksphere.repository.AcademicProfileRepository;
import com.tasksphere.repository.WorkspaceTaskRepository;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkspaceTaskAnalyticsService {
    private final WorkspaceTaskRepository taskRepository;
    private final AcademicProfileRepository profileRepository;

    public WorkspaceTaskAnalyticsService(
        WorkspaceTaskRepository taskRepository,
        AcademicProfileRepository profileRepository
    ) {
        this.taskRepository = taskRepository;
        this.profileRepository = profileRepository;
    }

    @Transactional(readOnly = true)
    public Analytics student(String username) {
        var profile = profile(username, "STUDENT");
        List<WorkspaceTask> allTasks = taskRepository.findByStudent_IdOrderByDueDateAscCreatedAtDesc(profile.getId());
        List<WorkspaceTask> personal = allTasks.stream().filter(task -> task.getAssignedBy() == null).toList();
        List<WorkspaceTask> assigned = allTasks.stream().filter(task -> task.getAssignedBy() != null).toList();
        LocalDate today = LocalDate.now();
        List<Long> completedDurations = allTasks.stream()
            .filter(task -> task.getStatus() == TaskStatus.COMPLETED && task.getCreatedAt() != null)
            .map(task -> Math.max(0, ChronoUnit.DAYS.between(task.getCreatedAt().toLocalDate(), task.getUpdatedAt().toLocalDate())))
            .toList();
        double averageDays = completedDurations.isEmpty() ? 0
            : Math.round(completedDurations.stream().mapToLong(Long::longValue).average().orElse(0) * 10.0) / 10.0;
        return new Analytics(
            allTasks.size(), completed(allTasks), rate(allTasks.size(), completed(allTasks)),
            personal.size(), completed(personal), rate(personal.size(), completed(personal)),
            assigned.size(), completed(assigned), rate(assigned.size(), completed(assigned)),
            completedBetween(allTasks, today.minusDays(6), today),
            completedBetween(allTasks, today.minusDays(13), today.minusDays(7)),
            averageDays,
            priorityBreakdown(allTasks), categoryBreakdown(allTasks), List.of()
        );
    }

    @Transactional(readOnly = true)
    public Analytics faculty(String username) {
        var faculty = profile(username, "FACULTY");
        List<WorkspaceTask> tasks = taskRepository.findByAssignedBy_IdOrderByDueDateAscCreatedAtDesc(faculty.getId());
        Map<String, long[]> byStudent = new LinkedHashMap<>();
        for (WorkspaceTask task : tasks) {
            String studentUsername = task.getStudent().getUsername();
            long[] counts = byStudent.computeIfAbsent(studentUsername, ignored -> new long[2]);
            counts[0]++;
            if (task.getStatus() == TaskStatus.COMPLETED) {
                counts[1]++;
            }
        }
        List<Breakdown> perStudent = byStudent.entrySet().stream()
            .map(entry -> {
                var student = tasks.stream()
                    .map(WorkspaceTask::getStudent)
                    .filter(profile -> profile.getUsername().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow();
                return breakdown(student.getFirstName() + " " + student.getLastName()
                    + " (" + student.getUsername() + ")", entry.getValue()[0], entry.getValue()[1]);
            })
            .toList();
        return new Analytics(
            tasks.size(), completed(tasks), rate(tasks.size(), completed(tasks)),
            0, 0, 0, tasks.size(), completed(tasks), rate(tasks.size(), completed(tasks)),
            0, 0, 0,
            priorityBreakdown(tasks), categoryBreakdown(tasks), perStudent
        );
    }

    private com.tasksphere.domain.AcademicProfile profile(String username, String role) {
        var profile = profileRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("Your TaskSphere profile is not available."));
        if (!role.equals(profile.getWorkspaceRole()) || !"ACTIVE".equals(profile.getAccountStatus())) {
            throw new AccessDeniedException("Task analytics is not available to this account.");
        }
        return profile;
    }

    private long completed(List<WorkspaceTask> tasks) {
        return tasks.stream().filter(task -> task.getStatus() == TaskStatus.COMPLETED).count();
    }

    private long completedBetween(List<WorkspaceTask> tasks, LocalDate start, LocalDate end) {
        return tasks.stream()
            .filter(task -> task.getStatus() == TaskStatus.COMPLETED && task.getUpdatedAt() != null)
            .filter(task -> {
                LocalDate completedDate = task.getUpdatedAt().toLocalDate();
                return !completedDate.isBefore(start) && !completedDate.isAfter(end);
            }).count();
    }

    private List<Breakdown> priorityBreakdown(List<WorkspaceTask> tasks) {
        List<Breakdown> rows = new ArrayList<>();
        for (TaskPriority priority : TaskPriority.values()) {
            List<WorkspaceTask> matches = tasks.stream().filter(task -> task.getPriority() == priority).toList();
            rows.add(breakdown(capitalize(priority.name()), matches.size(), completed(matches)));
        }
        return rows;
    }

    private List<Breakdown> categoryBreakdown(List<WorkspaceTask> tasks) {
        List<Breakdown> rows = new ArrayList<>();
        for (TaskCategory category : TaskCategory.values()) {
            List<WorkspaceTask> matches = tasks.stream().filter(task -> task.getCategory() == category).toList();
            rows.add(breakdown(capitalize(category.name()), matches.size(), completed(matches)));
        }
        return rows;
    }

    private Breakdown breakdown(String label, long total, long completed) {
        return new Breakdown(label, total, completed, rate(total, completed));
    }

    private long rate(long total, long completed) {
        return total == 0 ? 0 : Math.round(completed * 100.0 / total);
    }

    private String capitalize(String value) {
        return value.substring(0, 1) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    public record Breakdown(String label, long total, long completed, long rate) {}

    public record Analytics(
        long totalTasks,
        long completedTasks,
        long overallRate,
        long personalTotal,
        long personalCompleted,
        long personalRate,
        long assignedTotal,
        long assignedCompleted,
        long assignedRate,
        long completedThisWeek,
        long completedLastWeek,
        double avgCompletionDays,
        List<Breakdown> priorityBreakdown,
        List<Breakdown> categoryBreakdown,
        List<Breakdown> perStudent
    ) {}
}
