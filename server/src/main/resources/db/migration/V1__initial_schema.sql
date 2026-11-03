CREATE TABLE app_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320),
    display_name VARCHAR(200),
    picture_url VARCHAR(2048),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A verified provider subject, never an email address, identifies a person.
CREATE TABLE auth_identities (
    provider VARCHAR(32) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider, provider_subject)
);
CREATE INDEX auth_identities_user_idx ON auth_identities(user_id);

CREATE TABLE user_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    refresh_token_hash CHAR(64) NOT NULL UNIQUE CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    device_label VARCHAR(100),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    rotated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX user_sessions_active_user_idx ON user_sessions(user_id) WHERE revoked_at IS NULL;
CREATE INDEX user_sessions_expiry_idx ON user_sessions(expires_at);

-- Retain only hashes of rotated tokens so reuse can revoke the session family.
-- These rows may be pruned together with expired sessions after retention.
CREATE TABLE retired_session_tokens (
    token_hash CHAR(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    session_id UUID NOT NULL REFERENCES user_sessions(id) ON DELETE CASCADE,
    retired_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX retired_session_tokens_session_idx ON retired_session_tokens(session_id);

CREATE TABLE tasks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description TEXT CHECK (length(description) <= 10000),
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    priority SMALLINT NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 3),
    due_date DATE,
    due_at TIMESTAMPTZ,
    due_timezone VARCHAR(100),
    completed_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((due_date IS NULL AND due_at IS NULL AND due_timezone IS NULL)
        OR (due_date IS NOT NULL AND due_at IS NULL AND due_timezone IS NULL)
        OR (due_date IS NULL AND due_at IS NOT NULL AND due_timezone IS NOT NULL)),
    CHECK (completed = (completed_at IS NOT NULL))
);
CREATE INDEX tasks_active_owner_idx ON tasks(user_id, created_at DESC, id) WHERE deleted_at IS NULL;
CREATE INDEX tasks_dashboard_idx ON tasks(user_id, due_date, due_at)
    WHERE deleted_at IS NULL AND NOT completed AND (due_date IS NOT NULL OR due_at IS NOT NULL);

CREATE TABLE countdowns (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description TEXT CHECK (length(description) <= 10000),
    target_date DATE,
    target_at TIMESTAMPTZ,
    target_timezone VARCHAR(100),
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((target_date IS NOT NULL AND target_at IS NULL AND target_timezone IS NULL)
        OR (target_date IS NULL AND target_at IS NOT NULL AND target_timezone IS NOT NULL))
);
CREATE INDEX countdowns_active_owner_idx ON countdowns(user_id, created_at DESC, id) WHERE deleted_at IS NULL;
CREATE INDEX countdowns_dashboard_idx ON countdowns(user_id, target_date, target_at) WHERE deleted_at IS NULL;

-- The database owns timestamps and versions, including soft deletes.
CREATE FUNCTION advance_item_version() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.created_at = OLD.created_at;
    NEW.updated_at = clock_timestamp();
    NEW.version = OLD.version + 1;
    NEW.user_id = OLD.user_id;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tasks_advance_version BEFORE UPDATE ON tasks
    FOR EACH ROW EXECUTE FUNCTION advance_item_version();
CREATE TRIGGER countdowns_advance_version BEFORE UPDATE ON countdowns
    FOR EACH ROW EXECUTE FUNCTION advance_item_version();
