document.addEventListener("DOMContentLoaded", () => {
    const editModal = document.getElementById("editTaskModal");
    if (editModal) {
        editModal.addEventListener("show.bs.modal", event => {
            const button = event.relatedTarget;
            if (!button) return;
            const form = document.getElementById("editTaskForm");
            form.action = `/tasks/update/${button.dataset.taskId}`;
            document.getElementById("editTitle").value = button.dataset.title || "";
            document.getElementById("editDescription").value = button.dataset.description || "";
            document.getElementById("editCategory").value = button.dataset.category || "ASSIGNMENT";
            document.getElementById("editPriority").value = button.dataset.priority || "MEDIUM";
            document.getElementById("editStatus").value = button.dataset.status || "PENDING";
            document.getElementById("editDueDate").value = button.dataset.dueDate || "";
        });
    }

    const submitModal = document.getElementById("submitTaskModal");
    if (submitModal) {
        submitModal.addEventListener("show.bs.modal", event => {
            const button = event.relatedTarget;
            if (!button) return;
            document.getElementById("submitTaskForm").action = `/tasks/submit/${button.dataset.taskId}`;
            document.getElementById("submitTaskTitle").textContent = button.dataset.title || "this task";
            document.getElementById("submissionNote").value = "";
        });
    }

    const assignedEditModal = document.getElementById("editAssignedTaskModal");
    if (assignedEditModal) {
        assignedEditModal.addEventListener("show.bs.modal", event => {
            const button = event.relatedTarget;
            if (!button) return;
            document.getElementById("editAssignedTaskForm").action =
                `/tasks/assigned/update/${button.dataset.taskId}`;
            document.getElementById("facultyEditTitle").value = button.dataset.title || "";
            document.getElementById("facultyEditDescription").value = button.dataset.description || "";
            document.getElementById("facultyEditCategory").value = button.dataset.category || "ASSIGNMENT";
            document.getElementById("facultyEditPriority").value = button.dataset.priority || "MEDIUM";
            document.getElementById("facultyEditDueDate").value = button.dataset.dueDate || "";
        });
    }

    const filters = document.querySelectorAll("[data-filter]");
    const cards = document.querySelectorAll("[data-status-card]");
    filters.forEach(filter => filter.addEventListener("click", event => {
        event.preventDefault();
        filters.forEach(item => item.classList.remove("active"));
        filter.classList.add("active");
        const status = filter.dataset.filter;
        let visible = 0;
        cards.forEach(card => {
            const show = status === "ALL" || card.dataset.statusCard === status;
            card.hidden = !show;
            if (show) visible++;
        });
        const empty = document.getElementById("filteredEmptyState");
        if (empty) empty.hidden = visible !== 0;
    }));

    document.querySelectorAll(".alert-auto-dismiss").forEach(alert => {
        window.setTimeout(() => {
            if (window.bootstrap?.Alert) bootstrap.Alert.getOrCreateInstance(alert).close();
        }, 4000);
    });
});
