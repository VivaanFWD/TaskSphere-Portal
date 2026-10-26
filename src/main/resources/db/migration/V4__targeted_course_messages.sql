ALTER TABLE course_messages
    ADD COLUMN audience VARCHAR(16) NOT NULL DEFAULT 'ALL';

ALTER TABLE course_messages
    ADD COLUMN recipient_username VARCHAR(64);

ALTER TABLE course_messages
    ADD CONSTRAINT ck_course_message_audience CHECK (
        (audience = 'ALL' AND recipient_username IS NULL)
        OR (audience = 'INDIVIDUAL' AND recipient_username IS NOT NULL)
    );

CREATE INDEX ix_course_messages_recipient
    ON course_messages (course_id, recipient_username, created_at);
