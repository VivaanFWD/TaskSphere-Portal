ALTER TABLE assignment_submissions
    ADD COLUMN attachment_name VARCHAR(255);

ALTER TABLE assignment_submissions
    ADD COLUMN attachment_storage_key VARCHAR(36);

CREATE UNIQUE INDEX uq_submission_attachment_storage_key
    ON assignment_submissions (attachment_storage_key);
