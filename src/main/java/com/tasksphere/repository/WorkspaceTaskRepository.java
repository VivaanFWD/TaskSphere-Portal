package com.tasksphere.repository;

import com.tasksphere.domain.WorkspaceTask;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceTaskRepository extends JpaRepository<WorkspaceTask, Long> {
    @EntityGraph(attributePaths = {"student", "assignedBy"})
    List<WorkspaceTask> findByStudent_IdOrderByDueDateAscCreatedAtDesc(UUID studentId);

    @EntityGraph(attributePaths = {"student", "assignedBy"})
    List<WorkspaceTask> findByStudent_IdAndAssignedByIsNullOrderByDueDateAscCreatedAtDesc(UUID studentId);

    @EntityGraph(attributePaths = {"student", "assignedBy"})
    List<WorkspaceTask> findByStudent_IdAndAssignedByIsNotNullOrderByDueDateAscCreatedAtDesc(UUID studentId);

    @EntityGraph(attributePaths = {"student", "assignedBy"})
    List<WorkspaceTask> findByAssignedBy_IdOrderByDueDateAscCreatedAtDesc(UUID facultyId);

    @EntityGraph(attributePaths = {"student", "assignedBy"})
    java.util.Optional<WorkspaceTask> findWithProfilesById(Long id);
}
