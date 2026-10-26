CREATE TABLE academic_profiles (
    id UUID PRIMARY KEY,
    auth_user_id UUID NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL UNIQUE,
    first_name VARCHAR(120) NOT NULL,
    last_name VARCHAR(120) NOT NULL,
    email VARCHAR(320) NOT NULL UNIQUE,
    workspace_role VARCHAR(32) NOT NULL,
    account_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_academic_profiles_role CHECK (workspace_role IN ('STUDENT', 'FACULTY', 'ACADEMIC_OFFICE')),
    CONSTRAINT ck_academic_profiles_status CHECK (account_status IN ('ACTIVE', 'FROZEN', 'BLOCKED', 'DEACTIVATED'))
);

CREATE INDEX ix_academic_profiles_role ON academic_profiles (workspace_role);
CREATE INDEX ix_academic_profiles_name ON academic_profiles (last_name, first_name);
