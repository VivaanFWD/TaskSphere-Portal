CREATE TABLE academic_courses (
    id UUID PRIMARY KEY,
    code VARCHAR(32) NOT NULL UNIQUE,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    term VARCHAR(80) NOT NULL,
    faculty_user_id UUID NOT NULL REFERENCES academic_profiles(auth_user_id),
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX ix_academic_courses_faculty ON academic_courses (faculty_user_id);

CREATE TABLE course_enrollments (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL REFERENCES academic_courses(id),
    student_user_id UUID NOT NULL REFERENCES academic_profiles(auth_user_id),
    enrolled_by VARCHAR(64) NOT NULL,
    enrolled_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_course_enrollment UNIQUE (course_id, student_user_id)
);

CREATE INDEX ix_course_enrollments_student ON course_enrollments (student_user_id);

CREATE TABLE course_assignments (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL REFERENCES academic_courses(id),
    title VARCHAR(160) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    due_at TIMESTAMP NOT NULL,
    max_points DECIMAL(8, 2) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_course_assignment_points CHECK (max_points > 0)
);

CREATE INDEX ix_course_assignments_course_due ON course_assignments (course_id, due_at);

CREATE TABLE assignment_submissions (
    id UUID PRIMARY KEY,
    assignment_id UUID NOT NULL REFERENCES course_assignments(id),
    student_user_id UUID NOT NULL REFERENCES academic_profiles(auth_user_id),
    body VARCHAR(12000) NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    grade DECIMAL(8, 2),
    feedback VARCHAR(4000),
    graded_by VARCHAR(64),
    graded_at TIMESTAMP,
    CONSTRAINT uq_assignment_submission UNIQUE (assignment_id, student_user_id),
    CONSTRAINT ck_submission_grade CHECK (grade IS NULL OR grade >= 0)
);

CREATE INDEX ix_assignment_submissions_student ON assignment_submissions (student_user_id);

CREATE TABLE course_messages (
    id UUID PRIMARY KEY,
    course_id UUID NOT NULL REFERENCES academic_courses(id),
    author_username VARCHAR(64) NOT NULL,
    body VARCHAR(5000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX ix_course_messages_course_created ON course_messages (course_id, created_at);

CREATE TABLE academic_audit_events (
    id UUID PRIMARY KEY,
    actor_username VARCHAR(64) NOT NULL,
    action VARCHAR(80) NOT NULL,
    entity_type VARCHAR(40) NOT NULL,
    entity_id VARCHAR(64) NOT NULL,
    details VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX ix_academic_audit_created ON academic_audit_events (created_at);
