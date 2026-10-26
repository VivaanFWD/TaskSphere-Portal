ALTER TABLE academic_profiles
    ADD COLUMN designation VARCHAR(160) NOT NULL DEFAULT '';

ALTER TABLE academic_profiles
    ADD COLUMN department VARCHAR(160) NOT NULL DEFAULT '';

ALTER TABLE academic_profiles
    ADD COLUMN school VARCHAR(160) NOT NULL DEFAULT '';

ALTER TABLE academic_profiles
    ADD COLUMN office_location VARCHAR(120) NOT NULL DEFAULT '';

ALTER TABLE academic_profiles
    ADD COLUMN open_hours VARCHAR(2000) NOT NULL DEFAULT '';

ALTER TABLE course_assignments
    ADD COLUMN question_paper_name VARCHAR(255);

ALTER TABLE course_assignments
    ADD COLUMN question_paper_storage_key VARCHAR(36);

CREATE UNIQUE INDEX uq_assignment_question_paper_storage_key
    ON course_assignments (question_paper_storage_key);
